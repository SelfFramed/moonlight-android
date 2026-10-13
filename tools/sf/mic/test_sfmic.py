"""Tests for the microphone protocol, PC side: python -m unittest discover -s tools/sf/mic -p "test_*.py"

TEST_VECTOR_SHA256 is shared with app/src/test/java/com/limelight/selfframed/mic/MicPacketTest.java: both sides
seal the same input and must produce byte-identical packets.
"""
import hashlib
import os
import struct
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sfmic  # noqa: E402
from cryptography.hazmat.primitives.ciphers.aead import AESGCM  # noqa: E402

TEST_CODE = 'ABCD-EFGH-JKMN-PQRS-TVWX'
TEST_SESSION = 0x01020304
TEST_SENT = 1760000000
TEST_SEQ = 5
TEST_PCM = struct.pack('<480h', *[(i * 7) - 1600 for i in range(480)])
TEST_VECTOR_SHA256 = '68de1097f2e12fab233c022c13b417525cb91a08d8d74b84eae318336732437b'


class ProtocolTest(unittest.TestCase):
    def test_code_normalization(self):
        self.assertEqual('ABCDEFGHJKMNPQRSTVWX', sfmic.normalize_code(' abcd-efgh-jkmn-pqrs-tvwx '))
        self.assertEqual('0111' + 'A' * 16, sfmic.normalize_code('OILl' + 'a' * 16))
        self.assertIsNone(sfmic.normalize_code('ABCD-EFGH'))
        self.assertIsNone(sfmic.normalize_code('ABCD-EFGH-JKMN-PQRS-TVWU'))  # U is not in the alphabet
        code = sfmic.new_code()
        self.assertEqual(code, sfmic.normalize_code(sfmic.format_code(code)))

    def test_round_trip(self):
        key = sfmic.derive_key(sfmic.normalize_code(TEST_CODE))
        packet = sfmic.seal(key, TEST_SESSION, TEST_SEQ, TEST_PCM, TEST_SENT, sfmic.FLAG_END)
        self.assertEqual(20 + 960 + 16, len(packet))
        self.assertEqual(b'SM\x01\x01\x01\x02\x03\x04' + struct.pack('>IQ', TEST_SENT, TEST_SEQ), packet[:20])
        self.assertEqual((TEST_SESSION, TEST_SEQ, TEST_PCM, TEST_SENT, sfmic.FLAG_END),
                         sfmic.open_packet(AESGCM(key), packet))

    def test_rejects_tampering_and_wrong_key(self):
        key = sfmic.derive_key(sfmic.normalize_code(TEST_CODE))
        packet = sfmic.seal(key, TEST_SESSION, TEST_SEQ, TEST_PCM, TEST_SENT)
        aead = AESGCM(key)
        for index in (3, 11, 19, 100, len(packet) - 1):  # flags, time, sequence, audio, tag
            tampered = bytearray(packet)
            tampered[index] ^= 1
            self.assertIsNone(sfmic.open_packet(aead, bytes(tampered)), index)
        other = AESGCM(sfmic.derive_key(sfmic.new_code()))
        self.assertIsNone(sfmic.open_packet(other, packet))
        self.assertIsNone(sfmic.open_packet(aead, b'SM\x01'))

    def test_shared_vector(self):
        key = sfmic.derive_key(sfmic.normalize_code(TEST_CODE))
        digest = hashlib.sha256(sfmic.seal(key, TEST_SESSION, TEST_SEQ, TEST_PCM, TEST_SENT)).hexdigest()
        print('\nshared test vector sha256:', digest)
        if TEST_VECTOR_SHA256 != 'see test output':
            self.assertEqual(TEST_VECTOR_SHA256, digest)


if __name__ == '__main__':
    unittest.main()
