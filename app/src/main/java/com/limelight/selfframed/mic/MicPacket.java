package com.limelight.selfframed.mic;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.GeneralSecurityException;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * One microphone packet to the PC (pure Java, JVM-tested). Layout, same as tools/sf/mic/sfmic.py:
 * <pre>
 * 0   2  magic "SM"          8   4  sender time, Unix seconds (big-endian)
 * 2   1  version 1           12  8  sequence number (big-endian)
 * 3   1  flags (1 = end)     20  …  AES-128-GCM(PCM), the 20 header bytes as additional data, 16-byte tag
 * 4   4  session id (big-endian)
 * </pre>
 * Nonce = session id + sequence number. PCM: 10 ms at 48 kHz mono, 16-bit little-endian; 996 bytes per packet.
 * Not thread-safe; one instance per sending thread.
 */
public final class MicPacket {
    public static final int PORT = 48100;
    public static final int SAMPLE_RATE = 48000;
    public static final int FRAME_SAMPLES = 480;
    public static final int HEADER_BYTES = 20;
    public static final int TAG_BYTES = 16;
    public static final int PACKET_BYTES = HEADER_BYTES + FRAME_SAMPLES * 2 + TAG_BYTES;
    public static final int FLAG_END = 0x01;
    private static final int VERSION = 1;

    private final SecretKeySpec key;
    private final int session;
    private final Cipher cipher;
    private final ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN);
    private final ByteBuffer nonce = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN);
    private final ByteBuffer plain = ByteBuffer.allocate(FRAME_SAMPLES * 2).order(ByteOrder.LITTLE_ENDIAN);

    public MicPacket(byte[] key16, int session) throws GeneralSecurityException {
        this.key = new SecretKeySpec(key16, "AES");
        this.session = session;
        this.cipher = Cipher.getInstance("AES/GCM/NoPadding");
    }

    /** Seals one 10 ms frame ({@link #FRAME_SAMPLES} samples from {@code pcm[offset]}). */
    public byte[] seal(long seq, long sentSeconds, int flags, short[] pcm, int offset) throws GeneralSecurityException {
        header.clear();
        header.put((byte) 'S').put((byte) 'M').put((byte) VERSION).put((byte) flags)
                .putInt(session).putInt((int) sentSeconds).putLong(seq);
        nonce.clear();
        nonce.putInt(session).putLong(seq);
        plain.clear();
        for (int i = 0; i < FRAME_SAMPLES; i++) {
            plain.putShort(pcm[offset + i]);
        }
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BYTES * 8, nonce.array()));
        cipher.updateAAD(header.array());
        byte[] sealed = cipher.doFinal(plain.array());
        byte[] out = new byte[HEADER_BYTES + sealed.length];
        System.arraycopy(header.array(), 0, out, 0, HEADER_BYTES);
        System.arraycopy(sealed, 0, out, HEADER_BYTES, sealed.length);
        return out;
    }

    /** Opens a packet (tests and diagnostics): the samples, or null if it is not valid for this key. */
    public static short[] open(byte[] key16, byte[] packet) {
        if (packet.length < HEADER_BYTES + TAG_BYTES || packet[0] != 'S' || packet[1] != 'M' || packet[2] != VERSION) {
            return null;
        }
        ByteBuffer h = ByteBuffer.wrap(packet, 0, HEADER_BYTES).order(ByteOrder.BIG_ENDIAN);
        int session = h.getInt(4);
        long seq = h.getLong(12);
        byte[] n = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN).putInt(session).putLong(seq).array();
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key16, "AES"), new GCMParameterSpec(TAG_BYTES * 8, n));
            c.updateAAD(packet, 0, HEADER_BYTES);
            byte[] p = c.doFinal(packet, HEADER_BYTES, packet.length - HEADER_BYTES);
            ByteBuffer b = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN);
            short[] out = new short[p.length / 2];
            for (int i = 0; i < out.length; i++) {
                out[i] = b.getShort();
            }
            return out;
        } catch (GeneralSecurityException e) {
            return null;
        }
    }
}
