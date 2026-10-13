package com.limelight.selfframed.mic;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.security.MessageDigest;

public class MicPacketTest {
    // Shared with tools/sf/mic/test_sfmic.py: both sides must seal this input to identical bytes
    private static final String TEST_CODE = "ABCD-EFGH-JKMN-PQRS-TVWX";
    private static final int TEST_SESSION = 0x01020304;
    private static final long TEST_SENT = 1760000000L;
    private static final long TEST_SEQ = 5;
    private static final String TEST_VECTOR_SHA256 = "68de1097f2e12fab233c022c13b417525cb91a08d8d74b84eae318336732437b";

    private static short[] testPcm() {
        short[] pcm = new short[MicPacket.FRAME_SAMPLES];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (short) (i * 7 - 1600);
        }
        return pcm;
    }

    private static String sha256(byte[] data) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(data)) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    @Test
    public void sealsLikeThePcSide() throws Exception {
        byte[] key = MicCode.key(MicCode.normalize(TEST_CODE));
        byte[] packet = new MicPacket(key, TEST_SESSION).seal(TEST_SEQ, TEST_SENT, 0, testPcm(), 0);
        assertEquals(MicPacket.PACKET_BYTES, packet.length);
        assertEquals(TEST_VECTOR_SHA256, sha256(packet));
    }

    @Test
    public void roundTripAndRejection() throws Exception {
        byte[] key = MicCode.key(MicCode.normalize(TEST_CODE));
        MicPacket packer = new MicPacket(key, 42);
        short[] pcm = testPcm();
        byte[] first = packer.seal(0, TEST_SENT, 0, pcm, 0);
        byte[] second = packer.seal(1, TEST_SENT, MicPacket.FLAG_END, pcm, 0);
        assertArrayEquals(pcm, MicPacket.open(key, first));
        assertEquals(MicPacket.FLAG_END, second[3]);
        // Same audio, next sequence number: a different nonce gives a different ciphertext
        assertNotEquals(java.util.Arrays.toString(java.util.Arrays.copyOfRange(first, 20, 60)),
                java.util.Arrays.toString(java.util.Arrays.copyOfRange(second, 20, 60)));
        byte[] tampered = first.clone();
        tampered[12] ^= 1;
        assertNull(MicPacket.open(key, tampered));
        assertNull(MicPacket.open(MicCode.key("0000000000000000000A"), first));
    }

    @Test
    public void codeNormalization() {
        assertEquals("ABCDEFGHJKMNPQRSTVWX", MicCode.normalize(" abcd-efgh-jkmn-pqrs-tvwx "));
        assertEquals("0111AAAAAAAAAAAAAAAA", MicCode.normalize("OILlaaaaaaaaaaaaaaaa"));
        assertNull(MicCode.normalize("ABCD-EFGH"));
        assertNull(MicCode.normalize("ABCD-EFGH-JKMN-PQRS-TVWU"));
        assertNull(MicCode.normalize(null));
        assertEquals("ABCD-EFGH-JKMN-PQRS-TVWX", MicCode.format("ABCDEFGHJKMNPQRSTVWX"));
    }
}
