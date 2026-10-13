"""Records the recording side of the virtual microphone for a few seconds and prints level and pitch per 0.5 s,
to check what applications would hear. Records only the virtual device, never a real microphone.

Usage: python tools/sf/mic/measure-virtual-mic.py [--device "Steam Streaming Microphone"] [--seconds 5]
"""
import argparse
import sys

import numpy as np
import sounddevice as sd


def main(argv):
    parser = argparse.ArgumentParser()
    parser.add_argument('--device', default='Steam Streaming Microphone')
    parser.add_argument('--seconds', type=float, default=5.0)
    args = parser.parse_args(argv)
    apis = sd.query_hostapis()
    index = next((i for i, d in enumerate(sd.query_devices())
                  if d['max_input_channels'] > 0 and args.device.lower() in d['name'].lower()
                  and 'WASAPI' in apis[d['hostapi']]['name']), None)
    if index is None:
        sys.exit('no WASAPI recording device matching %r' % args.device)
    info = sd.query_devices(index)
    rate = int(info['default_samplerate'])
    data = sd.rec(int(args.seconds * rate), samplerate=rate, channels=1, dtype='float32', device=index)
    sd.wait()
    x = data[:, 0]
    print('device: %s (%d Hz)' % (info['name'], rate))
    win = rate // 2
    for k in range(0, len(x) - win + 1, win):
        seg = x[k:k + win]
        peak = float(np.max(np.abs(seg)))
        if peak < 1e-4:
            print('%4.1f s  silent' % (k / rate))
            continue
        spectrum = np.abs(np.fft.rfft(seg * np.hanning(len(seg))))
        hz = np.argmax(spectrum) * rate / len(seg)
        print('%4.1f s  peak %.4f  pitch %6.1f Hz' % (k / rate, peak, hz))


if __name__ == '__main__':
    main(sys.argv[1:])
