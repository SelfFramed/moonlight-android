package com.limelight.selfframed.mic;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.interfaces.ECPublicKey;

/**
 * Protocol v2 against the PC side: the same fixed private keys and inputs as tools/sf/mic/test_sfmic.py must give
 * byte-identical public keys, keys, code, tag and audio packet.
 */
public class MicPairingTest {
    private static final BigInteger PHONE_D = new BigInteger("11".repeat(32), 16);
    private static final BigInteger PC_D = new BigInteger("22".repeat(32), 16);
    private static final byte[] PAIRING_ID = {0, 1, 2, 3, 4, 5, 6, 7};
    private static final int DEVICE = 0x0A0B0C0D;
    private static final int SESSION = 0x01020304;
    private static final long SENT = 1760000000L;
    private static final long SEQ = 5;

    private static final String PHONE_PUB = "040217e617f0b6443928278f96999e69a23a4f2c152bdf6d6cdf66e5b80282d4ed1"
            + "94a7debcb97712d2dda3ca85aa8765a56f45fc758599652f2897c65306e5794";
    private static final String PC_PUB = "04d65a93977caa3d1b081852ff57a79e465f1660577304baead505dd3a48589cf35"
            + "0185e895372df6221ea3a137557e473fddb6755f05bd507c3c533fce9c91285";
    private static final String AUDIO_KEY = "8328bdd018b0a29ee11d0d0ad3b584d9";
    private static final String CONFIRM_KEY = "a783f7cdb73fef58d2449cfb07d9ea8c";
    private static final String SAS = "815 968";
    private static final String RESULT_TAG = "5fa9a3a5fb9c61b72d12b818fd004cb2";
    private static final String AUDIO_PACKET_SHA256 = "85e8dbd9f31c0370b9e28b7bd1c4671eaaffc0f6f714f79b860154ae03e8cd44";

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }

    private static short[] testPcm() {
        short[] pcm = new short[MicPacket.FRAME_SAMPLES];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (short) (i * 7 - 1600);
        }
        return pcm;
    }

    @Test
    public void matchesThePcSide() throws Exception {
        KeyPair phone = MicPairing.keyPairFromScalar(PHONE_D);
        KeyPair pc = MicPairing.keyPairFromScalar(PC_D);
        byte[] phonePub = MicPairing.publicBytes((ECPublicKey) phone.getPublic());
        byte[] pcPub = MicPairing.publicBytes((ECPublicKey) pc.getPublic());
        assertEquals(PHONE_PUB, hex(phonePub));
        assertEquals(PC_PUB, hex(pcPub));

        byte[][] keys = MicPairing.derive(phone.getPrivate(), pcPub, PAIRING_ID, phonePub, pcPub);
        assertEquals(AUDIO_KEY, hex(keys[0]));
        assertEquals(CONFIRM_KEY, hex(keys[1]));
        assertArrayEquals("the PC derives the same keys", keys[0],
                MicPairing.derive(pc.getPrivate(), phonePub, PAIRING_ID, phonePub, pcPub)[0]);
        assertEquals(SAS, MicPairing.sas(phonePub, pcPub));
        assertEquals(RESULT_TAG, hex(MicPairing.resultTag(keys[1], PAIRING_ID, MicPairing.ACCEPTED, DEVICE)));

        byte[] packet = new MicPacket(keys[0], DEVICE, SESSION).seal(SEQ, SENT, 0, testPcm(), 0);
        assertEquals(MicPacket.PACKET_BYTES, packet.length);
        assertEquals(AUDIO_PACKET_SHA256, hex(MessageDigest.getInstance("SHA-256").digest(packet)));
        assertArrayEquals(testPcm(), MicPacket.open(keys[0], packet));
    }

    @Test
    public void resultNeedsTheRightTag() throws Exception {
        byte[] confirm = new byte[16];
        byte[] body = new byte[21];
        body[0] = MicPairing.ACCEPTED;
        body[4] = 7;
        System.arraycopy(MicPairing.resultTag(confirm, PAIRING_ID, MicPairing.ACCEPTED, 7), 0, body, 5, 16);
        MicPairing.Result r = MicPairing.parseResult(confirm, PAIRING_ID, body);
        assertNotNull(r);
        assertEquals(7, r.device);
        body[20] ^= 1;
        assertNull(MicPairing.parseResult(confirm, PAIRING_ID, body));
        assertNull(MicPairing.parseResult(confirm, PAIRING_ID, new byte[20]));
    }

    @Test
    public void helloCommitsToTheKey() throws Exception {
        byte[] pub = MicPairing.publicBytes((ECPublicKey) MicPairing.newKeyPair().getPublic());
        byte[] hello = MicPairing.hello(PAIRING_ID, pub, "moto g(100)");
        MicPairing.Packet p = MicPairing.parse(hello, hello.length);
        assertNotNull(p);
        assertEquals(MicPairing.HELLO, p.type);
        assertArrayEquals(PAIRING_ID, p.pairingId);
        byte[] commit = java.util.Arrays.copyOf(p.body, 32);
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(pub), commit);
        assertEquals(11, p.body[32]);
        assertNull(MicPairing.parse(new byte[] {'S', 'M', 2, 1, 0, 0, 0, 0, 0, 0, 0, 0}, 12));
    }
}
