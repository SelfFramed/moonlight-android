"""Tests for the microphone protocol v2, PC side: python -m unittest discover -s tools/sf/mic -p "test_*.py"

The VECTOR values are shared with app/src/test/java/com/limelight/selfframed/mic/MicPairingTest.java: both sides
compute them from the same fixed private keys and inputs and must agree byte for byte.
"""
import hashlib
import os
import struct
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sfmic  # noqa: E402
from cryptography.hazmat.primitives.asymmetric import ec  # noqa: E402
from cryptography.hazmat.primitives.ciphers.aead import AESGCM  # noqa: E402

PHONE_D = int('11' * 32, 16)
PC_D = int('22' * 32, 16)
PAIRING_ID = bytes(range(8))
DEVICE = 0x0A0B0C0D
SESSION = 0x01020304
SENT = 1760000000
SEQ = 5
PCM = struct.pack('<480h', *[(i * 7) - 1600 for i in range(480)])

VECTOR = {
    'phone_pub': '040217e617f0b6443928278f96999e69a23a4f2c152bdf6d6cdf66e5b80282d4ed1'
                 '94a7debcb97712d2dda3ca85aa8765a56f45fc758599652f2897c65306e5794',
    'pc_pub': '04d65a93977caa3d1b081852ff57a79e465f1660577304baead505dd3a48589cf35'
              '0185e895372df6221ea3a137557e473fddb6755f05bd507c3c533fce9c91285',
    'audio_key': '8328bdd018b0a29ee11d0d0ad3b584d9',
    'confirm_key': 'a783f7cdb73fef58d2449cfb07d9ea8c',
    'sas': '815 968',
    'result_tag': '5fa9a3a5fb9c61b72d12b818fd004cb2',
    'audio_packet_sha256': '85e8dbd9f31c0370b9e28b7bd1c4671eaaffc0f6f714f79b860154ae03e8cd44',
}


def compute_vector():
    phone = ec.derive_private_key(PHONE_D, ec.SECP256R1())
    pc = ec.derive_private_key(PC_D, ec.SECP256R1())
    phone_pub, pc_pub = sfmic.public_bytes(phone), sfmic.public_bytes(pc)
    audio_key, confirm_key = sfmic.derive(phone, pc_pub, PAIRING_ID, phone_pub, pc_pub)
    return {
        'phone_pub': phone_pub.hex(),
        'pc_pub': pc_pub.hex(),
        'audio_key': audio_key.hex(),
        'confirm_key': confirm_key.hex(),
        'sas': sfmic.sas(phone_pub, pc_pub),
        'result_tag': sfmic.result_tag(confirm_key, PAIRING_ID, sfmic.ACCEPTED, DEVICE).hex(),
        'audio_packet_sha256': hashlib.sha256(sfmic.seal_audio(audio_key, DEVICE, SESSION, SEQ, PCM, SENT)).hexdigest(),
    }


class PairingTest(unittest.TestCase):
    def test_both_sides_derive_the_same_keys(self):
        phone = ec.derive_private_key(PHONE_D, ec.SECP256R1())
        pc = ec.derive_private_key(PC_D, ec.SECP256R1())
        phone_pub, pc_pub = sfmic.public_bytes(phone), sfmic.public_bytes(pc)
        self.assertEqual(sfmic.PUBLIC_KEY_BYTES, len(phone_pub))
        self.assertEqual(sfmic.derive(phone, pc_pub, PAIRING_ID, phone_pub, pc_pub),
                         sfmic.derive(pc, phone_pub, PAIRING_ID, phone_pub, pc_pub))

    def test_messages_round_trip(self):
        phone_pub = sfmic.public_bytes(ec.derive_private_key(PHONE_D, ec.SECP256R1()))
        packet = sfmic.pair_packet(sfmic.HELLO, PAIRING_ID, sfmic.hello_body(phone_pub, 'Moto G100'))
        kind, pid, body = sfmic.parse_pair(packet)
        self.assertEqual((sfmic.HELLO, PAIRING_ID), (kind, pid))
        self.assertEqual((sfmic.commitment(phone_pub), 'Moto G100'), sfmic.parse_hello(body))
        self.assertIsNone(sfmic.parse_pair(b'SM\x02\x01' + PAIRING_ID))
        self.assertIsNone(sfmic.parse_hello(b'\x00' * 32 + bytes([50]) + b'x' * 7))  # name longer than the body

    def test_audio_round_trip_and_tampering(self):
        key = bytes(range(16))
        packet = sfmic.seal_audio(key, DEVICE, SESSION, SEQ, PCM, SENT, sfmic.FLAG_END)
        self.assertEqual(1000, len(packet))
        self.assertEqual(DEVICE, sfmic.audio_device(packet))
        self.assertEqual((DEVICE, SESSION, SEQ, PCM, SENT, sfmic.FLAG_END), sfmic.open_audio(AESGCM(key), packet))
        for index in (3, 7, 11, 15, 23, 100, len(packet) - 1):
            tampered = bytearray(packet)
            tampered[index] ^= 1
            self.assertIsNone(sfmic.open_audio(AESGCM(key), bytes(tampered)), index)
        self.assertIsNone(sfmic.open_audio(AESGCM(bytes(16)), packet))

    def test_shared_vector(self):
        computed = compute_vector()
        print()
        for name, value in computed.items():
            print('vector %s = %s' % (name, value))
        for name, expected in VECTOR.items():
            if expected != 'see test output':
                self.assertEqual(expected, computed[name], name)


if __name__ == '__main__':
    unittest.main()
