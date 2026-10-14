"""SelfFramed microphone receiver v2: plays the phone's microphone into a virtual microphone on this PC.

Listens on UDP 48100 (LAN and Tailscale). Pairing works like Bluetooth: the first Ctrl+Alt+Shift+M on a phone
that is not paired yet opens a question on this PC with a 6-digit code; the same code shows in the stream; "Ja"
pairs the phone for good (sfmic.py describes the protocol). Paired phones are kept in
%USERPROFILE%\\.selfframed\\mic\\devices.json (delete an entry or the file to unpair).

Audio from a paired phone (AES-GCM, sender time within 2 minutes, rising sequence numbers) is played into the
playback side of a virtual microphone (default "Steam Streaming Microphone"). While it plays, the virtual
microphone is the Windows default recording device (all roles), so applications set to "default" use it without
any selection; afterwards the previous default comes back (also after a crash, at the next start). The output
opens with the first packet and closes on the end flag or after 2 s without packets.

Usage:
  pythonw tools/sf/mic/receiver.py                 (normally started at logon, see install-autostart.ps1)
  python  tools/sf/mic/receiver.py --console       (log to the console as well)
  Options: --device <name part> --port 48100 --target-ms 40 --keep-default (never change the default device)
  Tests only: --state-dir <dir> --auto-accept (pair without asking; needs --state-dir)
Log: %USERPROFILE%\\.selfframed\\mic\\receiver.log. Docs: docs/selfframed/ARCHITEKTUR.md, "Mikrofon".
"""
import argparse
import base64
import collections
import ctypes
import datetime
import json
import logging
import logging.handlers
import os
import secrets
import socket
import subprocess
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sfmic  # noqa: E402
import winaudio  # noqa: E402
from cryptography.hazmat.primitives.asymmetric import ec  # noqa: E402
from cryptography.hazmat.primitives.ciphers.aead import AESGCM  # noqa: E402

DEFAULT_STATE_DIR = os.path.join(os.path.expanduser('~'), '.selfframed', 'mic')
IDLE_CLOSE_S = 2.0
MAX_BUFFER_MS = 150
MAX_AGE_S = 120
PAIRING_TTL_S = 300
# Below the phone's 120 s wait, so a late click cannot pair a phone that already gave up
DIALOG_TIMEOUT_S = 110


class Devices:
    """Paired phones: device id -> name, key, time of pairing."""

    def __init__(self, path):
        self.path = path
        self.items = {}
        self.ciphers = {}
        if os.path.exists(path):
            with open(path, encoding='utf-8') as f:
                for dev_hex, entry in json.load(f).get('devices', {}).items():
                    self.items[int(dev_hex, 16)] = entry

    def cipher(self, device):
        entry = self.items.get(device)
        if entry is None:
            return None
        if device not in self.ciphers:
            self.ciphers[device] = AESGCM(base64.b64decode(entry['key']))
        return self.ciphers[device]

    def add(self, name, key, addr):
        device = secrets.randbits(32)
        while device in self.items or device == 0:
            device = secrets.randbits(32)
        self.items[device] = {'name': name, 'key': base64.b64encode(key).decode('ascii'), 'address': addr,
                              'paired': datetime.datetime.now().isoformat(timespec='seconds')}
        tmp = self.path + '.tmp'
        with open(tmp, 'w', encoding='utf-8') as f:
            json.dump({'devices': {'%08x' % d: e for d, e in self.items.items()}}, f, indent=1)
        os.replace(tmp, self.path)
        return device


class Pairing:
    """One pairing attempt, keyed by the phone's pairing id."""

    def __init__(self, commit, name, addr):
        self.commit = commit
        self.name = name
        self.addr = addr
        self.private = ec.generate_private_key(ec.SECP256R1())
        self.pc_pub = sfmic.public_bytes(self.private)
        self.phone_pub = None
        self.audio_key = None
        self.confirm_key = None
        self.status = sfmic.PENDING
        self.device = 0
        self.created = time.monotonic()


class DefaultMic:
    """Makes the virtual microphone the default recording device while audio plays, then restores."""

    def __init__(self, match, marker, log, enabled):
        self.match = match.lower()
        self.marker = marker
        self.log = log
        self.enabled = enabled
        self.previous = None

    def recover(self):
        """At start: undo a switch left over from a crash."""
        if os.path.exists(self.marker):
            with open(self.marker, encoding='utf-8') as f:
                self.previous = {int(k): v for k, v in json.load(f).items()}
            self.restore()

    def activate(self):
        if not self.enabled or self.previous is not None:
            return
        try:
            target = next((i for i, n in winaudio.capture_devices() if self.match in n.lower()), None)
            if target is None:
                self.log.warning('no recording device matching %r; default not changed', self.match)
                return
            previous = {r: winaudio.default_capture(r) for r in winaudio.ROLES}
            if all(previous[r] == target for r in winaudio.ROLES):
                return
            with open(self.marker, 'w', encoding='utf-8') as f:
                json.dump({str(k): v for k, v in previous.items()}, f)
            winaudio.set_default(target)
            self.previous = previous
            self.log.info('virtual microphone is the default recording device for now')
        except OSError as e:
            self.log.warning('could not change the default recording device: %s', e)

    def restore(self):
        if self.previous is None:
            return
        try:
            for role, device in self.previous.items():
                if device:
                    winaudio.set_default(device, [role])
            self.log.info('previous default recording device restored')
        except OSError as e:
            self.log.warning('could not restore the default recording device: %s', e)
        self.previous = None
        try:
            os.remove(self.marker)
        except OSError:
            pass


class Player:
    """Jitter buffer plus an output stream into the virtual device, opened on demand and closed when idle."""

    def __init__(self, device_match, target_ms, log, default_mic):
        self.device_match = device_match
        self.target_bytes = max(1, target_ms // 10) * sfmic.FRAME_BYTES
        self.max_bytes = (MAX_BUFFER_MS // 10) * sfmic.FRAME_BYTES
        self.log = log
        self.default_mic = default_mic
        self.buf = bytearray()
        self.lock = threading.Lock()
        self.stream = None
        self.prebuffering = True
        self.underruns = 0
        self.dropped = 0
        self.last_packet = 0.0
        self.device_error_logged = 0.0

    def _find_device(self):
        import sounddevice as sd
        apis = sd.query_hostapis()
        for index, dev in enumerate(sd.query_devices()):
            if (dev['max_output_channels'] > 0 and self.device_match.lower() in dev['name'].lower()
                    and 'WASAPI' in apis[dev['hostapi']]['name']):
                return index
        return None

    def push(self, pcm):
        self.last_packet = time.monotonic()
        with self.lock:
            self.buf += pcm
            if len(self.buf) > self.max_bytes:
                # Too far behind (e.g. after a network stall): keep the newest audio at the target delay
                cut = len(self.buf) - self.target_bytes
                del self.buf[:cut]
                self.dropped += cut // sfmic.FRAME_BYTES
        if self.stream is None:
            self._open()

    def end_of_session(self):
        self.last_packet = 0.0

    def _callback(self, outdata, frames, time_info, status):
        need = frames * 2
        with self.lock:
            if self.prebuffering and len(self.buf) >= self.target_bytes:
                self.prebuffering = False
            take = 0 if self.prebuffering else min(need, len(self.buf))
            if not self.prebuffering and take < need:
                # Ran dry: play what is there, then build the cushion up again
                self.underruns += 1
                self.prebuffering = True
            outdata[:take] = self.buf[:take]
            del self.buf[:take]
        if take < need:
            outdata[take:need] = b'\x00' * (need - take)

    def _open(self):
        import sounddevice as sd
        index = self._find_device()
        if index is None:
            now = time.monotonic()
            if now - self.device_error_logged > 60:
                self.log.error('no WASAPI playback device matching %r; enabled in the Sound settings?', self.device_match)
                self.device_error_logged = now
            with self.lock:
                self.buf.clear()
            return
        self.prebuffering = True
        self.stream = sd.RawOutputStream(samplerate=sfmic.SAMPLE_RATE, blocksize=sfmic.FRAME_SAMPLES, channels=1,
                                         dtype='int16', device=index, latency='low',
                                         extra_settings=sd.WasapiSettings(auto_convert=True), callback=self._callback)
        self.stream.start()
        self.default_mic.activate()
        self.log.info('microphone on: playing into %s', sd.query_devices(index)['name'])

    def close_if_idle(self):
        if self.stream is not None and time.monotonic() - self.last_packet > IDLE_CLOSE_S:
            self.stream.stop()
            self.stream.close()
            self.stream = None
            with self.lock:
                self.buf.clear()
            self.default_mic.restore()
            self.log.info('microphone off (underruns %d, dropped frames %d)', self.underruns, self.dropped)
            self.underruns = 0
            self.dropped = 0


def ask(name, addr, code, done):
    """Asks on this PC's screen with confirm_dialog.py (own process, always on top) and calls done(answer):
    True = allowed, False = rejected, None = no answer in time."""
    def run():
        dialog = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'confirm_dialog.py')
        try:
            proc = subprocess.run([sys.executable, dialog, '--name', name or 'Telefon', '--addr', addr,
                                   '--code', code, '--timeout', str(DIALOG_TIMEOUT_S)],
                                  creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0),
                                  timeout=DIALOG_TIMEOUT_S + 30)
            answer = {0: True, 1: False}.get(proc.returncode)
        except (OSError, subprocess.SubprocessError):
            answer = None
        done(answer)
    threading.Thread(target=run, daemon=True).start()


class Receiver:
    def __init__(self, args, log):
        self.args = args
        self.log = log
        state_dir = args.state_dir or DEFAULT_STATE_DIR
        os.makedirs(state_dir, exist_ok=True)
        old_code = os.path.join(state_dir, 'pairing.txt')
        if os.path.exists(old_code):
            os.remove(old_code)  # v1 pairing code, replaced by pairing on the screen
            log.info('removed the old pairing code file (v1)')
        self.devices = Devices(os.path.join(state_dir, 'devices.json'))
        self.default_mic = DefaultMic(args.device, os.path.join(state_dir, 'default-switched.json'), log,
                                      not args.keep_default)
        self.default_mic.recover()
        self.player = Player(args.device, args.target_ms, log, self.default_mic)
        self.pairings = {}
        self.lock = threading.Lock()
        self.dialog_open = False
        self.streams = {}  # (device, session) -> last sequence number
        self.rejected = 0
        self.last_reject_log = 0.0
        self.unknown_replies = {}

    def handle(self, sock, data, addr):
        if data[:2] == sfmic.PAIR_MAGIC:
            self._pairing(sock, data, addr)
        elif data[:2] == sfmic.AUDIO_MAGIC:
            self._audio(sock, data, addr)

    def _reply_result(self, sock, addr, pid, p):
        with self.lock:
            status, device = p.status, p.device
        sock.sendto(sfmic.pair_packet(sfmic.RESULT, pid, sfmic.result_body(p.confirm_key, pid, status, device)), addr)

    def _pairing(self, sock, data, addr):
        parsed = sfmic.parse_pair(data)
        if parsed is None:
            return
        kind, pid, body = parsed
        now = time.monotonic()
        with self.lock:
            for old in [k for k, v in self.pairings.items() if now - v.created > PAIRING_TTL_S]:
                del self.pairings[old]
            p = self.pairings.get(pid)
        if kind == sfmic.HELLO:
            hello = sfmic.parse_hello(body)
            if hello is None:
                return
            if p is None:
                p = Pairing(hello[0], hello[1], addr[0])
                with self.lock:
                    self.pairings[pid] = p
                self.log.info('pairing request from %s (%s)', addr[0], hello[1])
            sock.sendto(sfmic.pair_packet(sfmic.KEY, pid, p.pc_pub), addr)
        elif kind == sfmic.REVEAL and p is not None and len(body) == sfmic.PUBLIC_KEY_BYTES:
            if sfmic.commitment(body) != p.commit:
                self.log.warning('pairing from %s: key does not match its commitment', addr[0])
                return
            if p.audio_key is None:
                p.phone_pub = body
                try:
                    p.audio_key, p.confirm_key = sfmic.derive(p.private, body, pid, body, p.pc_pub)
                except ValueError:
                    return
                code = sfmic.sas(body, p.pc_pub)
                if self.args.auto_accept:
                    self._decide(pid, p, True)
                elif self.dialog_open:
                    with self.lock:
                        p.status = sfmic.BUSY
                else:
                    self.dialog_open = True
                    ask(p.name, addr[0], code, lambda ok, pid=pid, p=p: self._decide(pid, p, ok))
            self._reply_result(sock, addr, pid, p)

    def _decide(self, pid, p, accepted):
        """accepted: True, False, or None (no answer: stays pending until the phone gives up)."""
        with self.lock:
            if accepted:
                p.device = self.devices.add(p.name, p.audio_key, p.addr)
                p.status = sfmic.ACCEPTED
            elif accepted is not None:
                p.status = sfmic.REJECTED
        self.dialog_open = False
        self.log.info('pairing with %s (%s): %s', p.addr, p.name,
                      {True: 'accepted', False: 'rejected', None: 'no answer'}[accepted])

    def _audio(self, sock, data, addr):
        device = sfmic.audio_device(data)
        if device is None:
            return
        cipher = self.devices.cipher(device)
        if cipher is None:
            # Not paired (any more): tell the phone, at most once per second per address
            if time.monotonic() - self.unknown_replies.get(addr[0], 0) > 1:
                self.unknown_replies[addr[0]] = time.monotonic()
                sock.sendto(sfmic.pair_packet(sfmic.UNKNOWN, b'\x00' * 8, data[4:8]), addr)
            return
        packet = sfmic.open_audio(cipher, data)
        if packet is None or abs(time.time() - packet[4]) > MAX_AGE_S:
            self.rejected += 1
            if time.monotonic() - self.last_reject_log > 30:
                reason = 'damaged or forged' if packet is None else 'clock difference or replay'
                self.log.warning('rejected %d audio packet(s) from %s (%s)', self.rejected, addr[0], reason)
                self.last_reject_log = time.monotonic()
            return
        _device, session, seq, pcm, _sent, flags = packet
        key = (device, session)
        if key not in self.streams:
            self.streams = {key: -1}  # one phone at a time; a new session replaces the old one
            self.log.info('session %08x from %s (%s)', session, addr[0], self.devices.items[device].get('name'))
        if seq <= self.streams[key]:
            return
        self.streams[key] = seq
        if flags & sfmic.FLAG_END:
            self.player.end_of_session()
            return
        self.player.push(pcm)


def make_socket(port):
    try:
        sock = socket.socket(socket.AF_INET6, socket.SOCK_DGRAM)
        sock.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 0)
        sock.bind(('::', port))
    except OSError:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.bind(('0.0.0.0', port))
    sock.settimeout(0.5)
    return sock


def main(argv):
    parser = argparse.ArgumentParser(description='SelfFramed microphone receiver')
    parser.add_argument('--device', default='Steam Streaming Microphone')
    parser.add_argument('--port', type=int, default=sfmic.PORT)
    parser.add_argument('--target-ms', type=int, default=40)
    parser.add_argument('--keep-default', action='store_true', help='never change the default recording device')
    parser.add_argument('--console', action='store_true')
    parser.add_argument('--state-dir', help='tests: another place for devices and log')
    parser.add_argument('--auto-accept', action='store_true', help='tests: pair without asking (needs --state-dir)')
    args = parser.parse_args(argv)
    if args.auto_accept and not args.state_dir:
        parser.error('--auto-accept is for tests and needs --state-dir')

    state_dir = args.state_dir or DEFAULT_STATE_DIR
    os.makedirs(state_dir, exist_ok=True)
    log = logging.getLogger('sfmic')
    log.setLevel(logging.INFO)
    handler = logging.handlers.RotatingFileHandler(os.path.join(state_dir, 'receiver.log'), maxBytes=1_000_000,
                                                   backupCount=1, encoding='utf-8')
    handler.setFormatter(logging.Formatter('%(asctime)s %(levelname)s %(message)s'))
    log.addHandler(handler)
    if args.console:
        log.addHandler(logging.StreamHandler(sys.stdout))

    winaudio.init()
    try:
        sock = make_socket(args.port)
    except OSError as e:
        log.error('cannot listen on UDP %d (already running?): %s', args.port, e)
        return 1
    receiver = Receiver(args, log)
    log.info('listening on UDP %d, device %r, %d paired phone(s)%s', args.port, args.device,
             len(receiver.devices.items), '' if not args.keep_default else ', default device unchanged')
    while True:
        try:
            data, addr = sock.recvfrom(2048)
        except socket.timeout:
            receiver.player.close_if_idle()
            continue
        except OSError as e:
            log.warning('receive failed: %s', e)
            time.sleep(0.1)
            continue
        try:
            receiver.handle(sock, data, addr)
        except Exception as e:  # never let one bad packet stop the receiver
            log.exception('packet from %s: %s', addr[0], e)
        receiver.player.close_if_idle()


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
