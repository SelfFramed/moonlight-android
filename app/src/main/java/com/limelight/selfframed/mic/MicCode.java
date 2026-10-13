package com.limelight.selfframed.mic;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Locale;

/**
 * The pairing code between the app and the PC receiver (pure Java, JVM-tested): 20 characters of Crockford
 * base32 (100 bits), shown in groups of four. Same rules as tools/sf/mic/sfmic.py.
 */
public final class MicCode {
    public static final int LENGTH = 20;
    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    private MicCode() {
    }

    /**
     * @return the code as 20 alphabet characters, or null. Ignores case, spaces and dashes; O means 0, I and L
     *         mean 1.
     */
    public static String normalize(String text) {
        if (text == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (char ch : text.toUpperCase(Locale.ROOT).toCharArray()) {
            if (ch == ' ' || ch == '-' || ch == '_' || ch == '\t' || ch == '\r' || ch == '\n') {
                continue;
            }
            if (ch == 'O') {
                ch = '0';
            } else if (ch == 'I' || ch == 'L') {
                ch = '1';
            }
            if (ALPHABET.indexOf(ch) < 0) {
                return null;
            }
            sb.append(ch);
        }
        return sb.length() == LENGTH ? sb.toString() : null;
    }

    /** "ABCD-EFGH-JKMN-PQRS-TVWX" for a normalized code. */
    public static String format(String code) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < code.length(); i += 4) {
            if (i > 0) {
                sb.append('-');
            }
            sb.append(code, i, Math.min(code.length(), i + 4));
        }
        return sb.toString();
    }

    /** AES-128 key: the first 16 bytes of SHA-256("SelfFramed mic v1" + 0x00 + code). */
    public static byte[] key(String code) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update("SelfFramed mic v1".getBytes(StandardCharsets.US_ASCII));
            sha.update((byte) 0);
            sha.update(code.getBytes(StandardCharsets.US_ASCII));
            return Arrays.copyOf(sha.digest(), 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
