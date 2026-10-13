"""SelfFramed microphone protocol v2, PC side (shared by the receiver, the test sender and the tests).
Android side: com.limelight.selfframed.mic.MicPacket and MicPairing (byte-identical, shared test vectors).

UDP port 48100. Two kinds of packets:

Audio "SM" (phone -> PC), 1000 bytes:
    0  2  magic b'SM'           8  4  session id (random per microphone session)
    2  1  version 2             12 4  sender time, Unix seconds (freshness: no replays)
    3  1  flags (1 = last)      16 8  sequence number
    4  4  device id (from pairing)
    24 .. AES-128-GCM(PCM) with the 24 header bytes as additional data, 16-byte tag
    Nonce = session id + sequence number. PCM: 10 ms, 48 kHz mono, 16-bit little-endian (960 bytes).

Pairing "SP" (Bluetooth-style numeric comparison, ECDH P-256 with a commitment):
    0 2 magic b'SP', 2 1 version 2, 3 1 type, 4 8 pairing id (chosen by the phone), 12.. body
    1 HELLO  phone -> PC  SHA-256(phone public key) [32] + name length [1] + name (UTF-8)
    2 KEY    PC -> phone  PC public key [65, uncompressed]
    6 REVEAL phone -> PC  phone public key [65]; repeated until a final RESULT
    4 RESULT PC -> phone  status [1] (0 pending, 1 accepted, 2 rejected, 3 busy) + device id [4] + tag [16]
    5 UNKNOWN PC -> phone device id [4]: audio from a device the PC does not know (pair again)
Both sides: shared = ECDH; HKDF-SHA256(shared, salt=pairing id, info=INFO + phone pub + PC pub) -> 32 bytes:
audio key [16] + confirm key [16]. Code shown on both screens = first 4 bytes of SHA-256(b"SAS" + phone pub +
PC pub) mod 1,000,000. The phone commits to its key before it sees the PC's, so a man in the middle cannot search
for keys that give equal codes. tag = HMAC-SHA256(confirm key, b"SP-RESULT" + pairing id + status + device id)[:16].
"""
import hashlib
import hmac
import struct

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

PORT = 48100
SAMPLE_RATE = 48000
FRAME_SAMPLES = 480
FRAME_BYTES = FRAME_SAMPLES * 2
VERSION = 2
TAG_BYTES = 16
FLAG_END = 0x01

AUDIO_MAGIC = b'SM'
AUDIO_HEADER = struct.Struct('>2sBBIIIQ')  # magic, version, flags, device, session, time, seq (24 bytes)

PAIR_MAGIC = b'SP'
PAIR_HEADER = struct.Struct('>2sBB8s')  # magic, version, type, pairing id (12 bytes)
HELLO, KEY, STATUS_UNUSED, RESULT, UNKNOWN, REVEAL = 1, 2, 3, 4, 5, 6
PENDING, ACCEPTED, REJECTED, BUSY = 0, 1, 2, 3
PUBLIC_KEY_BYTES = 65
INFO = b'SelfFramed mic pairing v2'


# --- audio -------------------------------------------------------------------------------------

def seal_audio(key, device, session, seq, pcm, sent=0, flags=0):
    header = AUDIO_HEADER.pack(AUDIO_MAGIC, VERSION, flags, device, session, sent, seq)
    return header + AESGCM(key).encrypt(struct.pack('>IQ', session, seq), pcm, header)


def audio_device(data):
    """Device id of an audio packet, or None if it is not one."""
    if len(data) < AUDIO_HEADER.size + TAG_BYTES or data[:2] != AUDIO_MAGIC or data[2] != VERSION:
        return None
    return struct.unpack_from('>I', data, 4)[0]


def open_audio(aead, data):
    """(device, session, seq, pcm, sent, flags) for a valid packet, else None. aead = AESGCM(key)."""
    if audio_device(data) is None:
        return None
    _magic, _version, flags, device, session, sent, seq = AUDIO_HEADER.unpack_from(data)
    header = data[:AUDIO_HEADER.size]
    try:
        pcm = aead.decrypt(struct.pack('>IQ', session, seq), data[AUDIO_HEADER.size:], header)
    except InvalidTag:
        return None
    return device, session, seq, pcm, sent, flags


# --- pairing -----------------------------------------------------------------------------------

def public_bytes(private_key):
    return private_key.public_key().public_bytes(serialization.Encoding.X962,
                                                 serialization.PublicFormat.UncompressedPoint)


def commitment(phone_pub):
    return hashlib.sha256(phone_pub).digest()


def derive(private_key, peer_pub, pairing_id, phone_pub, pc_pub):
    """(audio key, confirm key) from our private key and the peer's public key."""
    peer = ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), peer_pub)
    shared = private_key.exchange(ec.ECDH(), peer)
    okm = HKDF(algorithm=hashes.SHA256(), length=32, salt=pairing_id, info=INFO + phone_pub + pc_pub).derive(shared)
    return okm[:16], okm[16:]


def sas(phone_pub, pc_pub):
    """The 6-digit code both screens show, e.g. '482 193'."""
    n = struct.unpack('>I', hashlib.sha256(b'SAS' + phone_pub + pc_pub).digest()[:4])[0] % 1000000
    return '%03d %03d' % (n // 1000, n % 1000)


def result_tag(confirm_key, pairing_id, status, device):
    return hmac.new(confirm_key, b'SP-RESULT' + pairing_id + bytes([status]) + struct.pack('>I', device),
                    hashlib.sha256).digest()[:TAG_BYTES]


def pair_packet(kind, pairing_id, body=b''):
    return PAIR_HEADER.pack(PAIR_MAGIC, VERSION, kind, pairing_id) + body


def parse_pair(data):
    """(type, pairing id, body) of a pairing packet, else None."""
    if len(data) < PAIR_HEADER.size or data[:2] != PAIR_MAGIC or data[2] != VERSION:
        return None
    _magic, _version, kind, pairing_id = PAIR_HEADER.unpack_from(data)
    return kind, pairing_id, data[PAIR_HEADER.size:]


def hello_body(phone_pub, name):
    raw = name.encode('utf-8')[:60]
    return commitment(phone_pub) + bytes([len(raw)]) + raw


def parse_hello(body):
    """(commitment, name) or None."""
    if len(body) < 33 or len(body) < 33 + body[32]:
        return None
    return body[:32], body[33:33 + body[32]].decode('utf-8', 'replace')


def result_body(confirm_key, pairing_id, status, device):
    return bytes([status]) + struct.pack('>I', device) + result_tag(confirm_key, pairing_id, status, device)
