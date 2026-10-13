"""SelfFramed microphone protocol, PC side (shared by the receiver, the test sender and the tests).

The phone sends its microphone as UDP packets to the PC (port 48100):

    offset size  field
    0      2     magic b'SM'
    2      1     version (1)
    3      1     flags (bit 0: last packet of the session)
    4      4     session id (random per microphone session, big-endian)
    8      4     sender time, Unix seconds (big-endian; freshness, so old packets cannot be replayed)
    12     8     sequence number (big-endian, +1 per packet)
    20     n+16  AES-128-GCM(PCM) with the 20 header bytes as additional data, tag appended

Nonce = session id + sequence number (12 bytes), unique per key. PCM: 10 ms, 48 kHz, mono, signed 16 bit
little-endian (480 samples, 960 bytes); a packet is 996 bytes, below Tailscale's 1280-byte MTU.
Key = first 16 bytes of SHA-256("SelfFramed mic v1" + 0x00 + pairing code), the code being 20 characters of
Crockford base32 (100 bits). The Android side is com.limelight.selfframed.mic.MicPacket / MicCode.
"""
import hashlib
import secrets
import struct

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

PORT = 48100
SAMPLE_RATE = 48000
FRAME_SAMPLES = 480
FRAME_BYTES = FRAME_SAMPLES * 2
HEADER = struct.Struct('>2sBBIIQ')
MAGIC = b'SM'
VERSION = 1
TAG_BYTES = 16
FLAG_END = 0x01
ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ'
CODE_LENGTH = 20


def normalize_code(text):
    """The code as 20 alphabet characters, or None. Ignores case, spaces and dashes; O means 0, I and L mean 1."""
    if text is None:
        return None
    s = ''.join(ch for ch in text.upper() if ch not in ' -_\t\r\n')
    s = s.replace('O', '0').replace('I', '1').replace('L', '1')
    if len(s) != CODE_LENGTH or any(ch not in ALPHABET for ch in s):
        return None
    return s


def format_code(code):
    return '-'.join(code[i:i + 4] for i in range(0, len(code), 4))


def new_code():
    return ''.join(secrets.choice(ALPHABET) for _ in range(CODE_LENGTH))


def derive_key(code):
    return hashlib.sha256(b'SelfFramed mic v1\x00' + code.encode('ascii')).digest()[:16]


def seal(key, session, seq, pcm, sent=0, flags=0):
    header = HEADER.pack(MAGIC, VERSION, flags, session, sent, seq)
    nonce = struct.pack('>IQ', session, seq)
    return header + AESGCM(key).encrypt(nonce, pcm, header)


def open_packet(aead, data):
    """(session, seq, pcm, sent, flags) for a valid packet, else None. aead = AESGCM(key)."""
    if len(data) < HEADER.size + TAG_BYTES:
        return None
    magic, version, flags, session, sent, seq = HEADER.unpack_from(data)
    if magic != MAGIC or version != VERSION:
        return None
    header = data[:HEADER.size]
    try:
        pcm = aead.decrypt(struct.pack('>IQ', session, seq), data[HEADER.size:], header)
    except InvalidTag:
        return None
    return session, seq, pcm, sent, flags
