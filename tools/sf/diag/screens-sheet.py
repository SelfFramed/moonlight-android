"""Contact sheet of emulator screenshots: crops the same band from every PNG in a folder and stacks
the crops into one image, so a whole test sequence (e.g. the notices at the top centre) can be
checked in one view. Standard library only.

Usage: python -I tools/sf/diag/screens-sheet.py <folder> [x0 y0 x1 y1] [--step 2]
Defaults: band x 300..1300, y 0..300 (top centre of a 1600x1200 screen), every 2nd pixel.
Writes <folder>/sheet.png from the files [0-9]*.png in name order.
Take the screenshots binary-safe from Git Bash: adb -s <serial> exec-out screencap -p > 01-name.png
(a PowerShell redirect corrupts PNG files).
"""
import glob
import os
import struct
import sys
import zlib


def read_png(path):
    data = open(path, 'rb').read()
    if data[:8] != b'\x89PNG\r\n\x1a\n':
        raise ValueError(path + ': not a PNG')
    pos, idat = 8, b''
    w = h = ct = None
    while pos < len(data):
        length, kind = struct.unpack('>I4s', data[pos:pos + 8])
        chunk = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if kind == b'IHDR':
            w, h, depth, ct = struct.unpack('>IIBB', chunk[:10])
            if depth != 8 or ct not in (2, 6):
                raise ValueError(path + ': only 8-bit RGB/RGBA')
        elif kind == b'IDAT':
            idat += chunk
        elif kind == b'IEND':
            break
    bpp = 4 if ct == 6 else 3
    raw = zlib.decompress(idat)
    stride = w * bpp
    rows, prev, i = [], bytearray(stride), 0
    for _ in range(h):
        f = raw[i]
        i += 1
        line = bytearray(raw[i:i + stride])
        i += stride
        for x in range(stride):
            a = line[x - bpp] if x >= bpp else 0
            b = prev[x]
            c = prev[x - bpp] if x >= bpp else 0
            if f == 1:
                line[x] = (line[x] + a) & 255
            elif f == 2:
                line[x] = (line[x] + b) & 255
            elif f == 3:
                line[x] = (line[x] + ((a + b) >> 1)) & 255
            elif f == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[x] = (line[x] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        rows.append(bytes(line))
        prev = line
    return w, h, bpp, rows


def write_png(path, w, rows):
    raw = b''.join(b'\x00' + r for r in rows)

    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data) & 0xffffffff)

    with open(path, 'wb') as f:
        f.write(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, len(rows), 8, 2, 0, 0, 0))
                + chunk(b'IDAT', zlib.compress(raw, 6)) + chunk(b'IEND', b''))


def main(argv):
    args = [a for a in argv[1:] if not a.startswith('--')]
    step = 2
    if '--step' in argv:
        step = int(argv[argv.index('--step') + 1])
        args = [a for a in args if a != str(step)]
    folder = args[0]
    x0, y0, x1, y1 = (int(v) for v in args[1:5]) if len(args) >= 5 else (300, 0, 1300, 300)
    width = (x1 - x0 + step - 1) // step
    out = []
    files = sorted(f for f in glob.glob(os.path.join(folder, '[0-9]*.png')))
    for f in files:
        w, h, bpp, rows = read_png(f)
        for y in range(y0, min(y1, h), step):
            r = rows[y]
            out.append(b''.join(r[x * bpp:x * bpp + 3] for x in range(x0, min(x1, w), step)).ljust(width * 3, b'\x00'))
        out += [b'\x00\x00\x00' * width] * 3
    write_png(os.path.join(folder, 'sheet.png'), width, out)
    print('sheet.png from %d screenshots' % len(files))


if __name__ == '__main__':
    main(sys.argv)
