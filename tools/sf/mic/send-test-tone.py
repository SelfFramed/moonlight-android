"""Test sender: sends a tone like the phone would (10 ms packets, 48 kHz mono), for testing the receiver on the PC.

Usage: python tools/sf/mic/send-test-tone.py [--host 127.0.0.1] [--port 48100] [--seconds 3] [--hz 440]
                                             [--amplitude 0.05] [--code <code>]
Without --code it reads the receiver's pairing file. The tone goes into the virtual microphone, nowhere else.
"""
import argparse
import math
import os
import random
import socket
import struct
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sfmic  # noqa: E402
from receiver import CODE_FILE  # noqa: E402


def main(argv):
    parser = argparse.ArgumentParser()
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=sfmic.PORT)
    parser.add_argument('--seconds', type=float, default=3.0)
    parser.add_argument('--hz', type=float, default=440.0)
    parser.add_argument('--amplitude', type=float, default=0.05)
    parser.add_argument('--code')
    args = parser.parse_args(argv)
    if args.code:
        code = sfmic.normalize_code(args.code)
    else:
        with open(CODE_FILE, encoding='utf-8') as f:
            code = sfmic.normalize_code(f.readline())
    key = sfmic.derive_key(code)
    session = random.getrandbits(32)
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    packets = int(args.seconds * 100)
    start = time.monotonic()
    for seq in range(packets + 1):
        last = seq == packets
        samples = [0] * sfmic.FRAME_SAMPLES if last else [
            int(32767 * args.amplitude * math.sin(2 * math.pi * args.hz * (seq * sfmic.FRAME_SAMPLES + i) / sfmic.SAMPLE_RATE))
            for i in range(sfmic.FRAME_SAMPLES)]
        pcm = struct.pack('<%dh' % sfmic.FRAME_SAMPLES, *samples)
        sock.sendto(sfmic.seal(key, session, seq, pcm, int(time.time()), sfmic.FLAG_END if last else 0),
                    (args.host, args.port))
        delay = start + (seq + 1) * 0.01 - time.monotonic()
        if delay > 0:
            time.sleep(delay)
    print('sent %d packets (%.1f s, %g Hz) to %s:%d' % (packets, args.seconds, args.hz, args.host, args.port))


if __name__ == '__main__':
    main(sys.argv[1:])
