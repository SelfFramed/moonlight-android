"""SelfFramed microphone receiver: plays the phone's microphone into a virtual microphone on this PC.

Listens on UDP 48100 (LAN and Tailscale) and plays every valid packet into the playback side of a virtual
microphone device (default: "Steam Streaming Microphone"); applications record from its recording side,
"Mikrofon (Steam Streaming Microphone)". Only packets sealed with the pairing code are accepted (AES-GCM,
sender time within 2 minutes, increasing sequence numbers). The output stream is opened when packets arrive and
closed after 2 s without packets, so the virtual microphone is silent otherwise.

First run creates the pairing code in %USERPROFILE%\\.selfframed\\mic\\pairing.txt. Enter it once in the app:
Einstellungen -> Ton (SelfFramed) -> Mikrofon-Kopplungscode. Log: %USERPROFILE%\\.selfframed\\mic\\receiver.log.

Usage:
  pythonw tools/sf/mic/receiver.py                    (normally started at logon, see install-autostart.ps1)
  python  tools/sf/mic/receiver.py --console          (log to the console as well)
  Options: --device <name part> --port 48100 --target-ms 40
Protocol: sfmic.py. Docs: docs/selfframed/ARCHITEKTUR.md, "Mikrofon".
"""
import argparse
import collections
import logging
import logging.handlers
import os
import socket
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sfmic  # noqa: E402
from cryptography.hazmat.primitives.ciphers.aead import AESGCM  # noqa: E402

STATE_DIR = os.path.join(os.path.expanduser('~'), '.selfframed', 'mic')
CODE_FILE = os.path.join(STATE_DIR, 'pairing.txt')
LOG_FILE = os.path.join(STATE_DIR, 'receiver.log')
IDLE_CLOSE_S = 2.0
MAX_BUFFER_MS = 150
MAX_AGE_S = 120


def load_or_create_code():
    os.makedirs(STATE_DIR, exist_ok=True)
    if os.path.exists(CODE_FILE):
        with open(CODE_FILE, encoding='utf-8') as f:
            first = f.readline().strip()
        code = sfmic.normalize_code(first)
        if code:
            return code
    code = sfmic.new_code()
    with open(CODE_FILE, 'w', encoding='utf-8') as f:
        f.write(sfmic.format_code(code) + '\n\n')
        f.write('Kopplungscode für Moonlight E-Ink (Mikrofon zum PC):\n'
                'In der App unter Einstellungen -> Ton (SelfFramed) -> Mikrofon-Kopplungscode eintragen.\n'
                'Neuer Code: diese Datei löschen und den Empfänger neu starten (dann in der App neu eintragen).\n')
    return code


class Player:
    """Jitter buffer plus an output stream into the virtual device, opened on demand and closed when idle."""

    def __init__(self, device_match, target_ms, log):
        self.device_match = device_match
        self.target_bytes = max(1, target_ms // 10) * sfmic.FRAME_BYTES
        self.max_bytes = (MAX_BUFFER_MS // 10) * sfmic.FRAME_BYTES
        self.log = log
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
            if self.prebuffering:
                take = 0
            else:
                take = min(need, len(self.buf))
                if take < need:
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
        self.log.info('microphone on: playing into %s', sd.query_devices(index)['name'])

    def close_if_idle(self):
        if self.stream is not None and time.monotonic() - self.last_packet > IDLE_CLOSE_S:
            self.stream.stop()
            self.stream.close()
            self.stream = None
            with self.lock:
                self.buf.clear()
            self.log.info('microphone off (underruns %d, dropped frames %d)', self.underruns, self.dropped)
            self.underruns = 0
            self.dropped = 0


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
    parser.add_argument('--console', action='store_true')
    args = parser.parse_args(argv)

    code = load_or_create_code()
    log = logging.getLogger('sfmic')
    log.setLevel(logging.INFO)
    handler = logging.handlers.RotatingFileHandler(LOG_FILE, maxBytes=1_000_000, backupCount=1, encoding='utf-8')
    handler.setFormatter(logging.Formatter('%(asctime)s %(levelname)s %(message)s'))
    log.addHandler(handler)
    if args.console:
        log.addHandler(logging.StreamHandler(sys.stdout))

    try:
        sock = make_socket(args.port)
    except OSError as e:
        log.error('cannot listen on UDP %d (already running?): %s', args.port, e)
        return 1
    log.info('listening on UDP %d, device %r, target %d ms', args.port, args.device, args.target_ms)

    aead = AESGCM(sfmic.derive_key(code))
    player = Player(args.device, args.target_ms, log)
    session = None
    last_seq = -1
    rejected = 0
    last_reject_log = 0.0
    while True:
        try:
            data, addr = sock.recvfrom(2048)
        except socket.timeout:
            player.close_if_idle()
            continue
        except OSError as e:
            log.warning('receive failed: %s', e)
            time.sleep(0.1)
            continue
        packet = sfmic.open_packet(aead, data)
        now = time.time()
        if packet is None or abs(now - packet[3]) > MAX_AGE_S:
            rejected += 1
            mono = time.monotonic()
            if mono - last_reject_log > 30:
                reason = 'wrong code or damaged' if packet is None else 'clock difference or replay'
                log.warning('rejected %d packet(s) from %s (%s)', rejected, addr[0], reason)
                last_reject_log = mono
            continue
        pkt_session, seq, pcm, _sent, flags = packet
        if pkt_session != session:
            session = pkt_session
            last_seq = -1
            log.info('session %08x from %s', session, addr[0])
        if seq <= last_seq:
            continue
        last_seq = seq
        if flags & sfmic.FLAG_END:
            player.end_of_session()
            continue
        player.push(pcm)
        player.close_if_idle()


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
