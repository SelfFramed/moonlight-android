"""Test sender, acting as a phone: pairs with the receiver (protocol v2), then sends a tone in 10 ms packets.
Also the reference implementation of the phone side of the pairing (the app's MicPairing does the same).

Usage: python tools/sf/mic/send-test-tone.py [--host 127.0.0.1] [--port 48100] [--seconds 3] [--hz 440]
                                             [--amplitude 0.05] [--name "Testsender"]
Against the real receiver this asks on the PC screen; for automatic tests run a receiver with
--state-dir <temp> --auto-accept --keep-default on another port.
"""
import argparse
import math
import os
import socket
import struct
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sfmic  # noqa: E402
from cryptography.hazmat.primitives.asymmetric import ec  # noqa: E402


def receive(sock, timeout):
    sock.settimeout(timeout)
    try:
        return sock.recv(2048)
    except socket.timeout:
        return None


def pair(sock, name, timeout=90):
    """(device id, audio key) after the PC accepted, else raises."""
    private = ec.generate_private_key(ec.SECP256R1())
    phone_pub = sfmic.public_bytes(private)
    pid = os.urandom(8)
    hello = sfmic.pair_packet(sfmic.HELLO, pid, sfmic.hello_body(phone_pub, name))
    pc_pub = None
    deadline = time.monotonic() + 10
    while pc_pub is None:
        if time.monotonic() > deadline:
            raise RuntimeError('the PC does not answer (receiver running?)')
        sock.send(hello)
        reply = sfmic.parse_pair(receive(sock, 1.0) or b'')
        if reply and reply[0] == sfmic.KEY and reply[1] == pid and len(reply[2]) == sfmic.PUBLIC_KEY_BYTES:
            pc_pub = reply[2]
    audio_key, confirm_key = sfmic.derive(private, pc_pub, pid, phone_pub, pc_pub)
    print('pairing code: %s (confirm on the PC)' % sfmic.sas(phone_pub, pc_pub))
    reveal = sfmic.pair_packet(sfmic.REVEAL, pid, phone_pub)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        sock.send(reveal)
        reply = sfmic.parse_pair(receive(sock, 1.0) or b'')
        if not reply or reply[0] != sfmic.RESULT or reply[1] != pid or len(reply[2]) != 21:
            continue
        status, device, tag = reply[2][0], struct.unpack('>I', reply[2][1:5])[0], reply[2][5:]
        if tag != sfmic.result_tag(confirm_key, pid, status, device):
            continue
        if status == sfmic.ACCEPTED:
            return device, audio_key
        if status in (sfmic.REJECTED, sfmic.BUSY):
            raise RuntimeError('pairing %s on the PC' % ('rejected' if status == sfmic.REJECTED else 'busy'))
    raise RuntimeError('no decision on the PC')


def main(argv):
    parser = argparse.ArgumentParser()
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=sfmic.PORT)
    parser.add_argument('--seconds', type=float, default=3.0)
    parser.add_argument('--hz', type=float, default=440.0)
    parser.add_argument('--amplitude', type=float, default=0.05)
    parser.add_argument('--name', default='Testsender')
    args = parser.parse_args(argv)
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.connect((args.host, args.port))
    device, key = pair(sock, args.name)
    print('paired as device %08x' % device)
    session = struct.unpack('>I', os.urandom(4))[0]
    packets = int(args.seconds * 100)
    start = time.monotonic()
    for seq in range(packets + 1):
        last = seq == packets
        samples = [0] * sfmic.FRAME_SAMPLES if last else [
            int(32767 * args.amplitude * math.sin(2 * math.pi * args.hz * (seq * sfmic.FRAME_SAMPLES + i) / sfmic.SAMPLE_RATE))
            for i in range(sfmic.FRAME_SAMPLES)]
        pcm = struct.pack('<%dh' % sfmic.FRAME_SAMPLES, *samples)
        sock.send(sfmic.seal_audio(key, device, session, seq, pcm, int(time.time()), sfmic.FLAG_END if last else 0))
        delay = start + (seq + 1) * 0.01 - time.monotonic()
        if delay > 0:
            time.sleep(delay)
    print('sent %d packets (%.1f s, %g Hz) to %s:%d' % (packets, args.seconds, args.hz, args.host, args.port))


if __name__ == '__main__':
    main(sys.argv[1:])
