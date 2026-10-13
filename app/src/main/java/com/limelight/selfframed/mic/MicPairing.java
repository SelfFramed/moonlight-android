package com.limelight.selfframed.mic;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;

import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Pairing with the PC receiver, phone side (pure Java, JVM-tested against shared vectors). Bluetooth-style numeric
 * comparison: ECDH on P-256, the phone commits to its key first, both screens show a 6-digit code, the PC owner
 * confirms. Messages and derivations are the same as tools/sf/mic/sfmic.py (see there for the layout).
 */
public final class MicPairing {
    public static final int HELLO = 1;
    public static final int KEY = 2;
    public static final int RESULT = 4;
    public static final int UNKNOWN = 5;
    public static final int REVEAL = 6;
    public static final int PENDING = 0;
    public static final int ACCEPTED = 1;
    public static final int REJECTED = 2;
    public static final int BUSY = 3;
    public static final int PUBLIC_KEY_BYTES = 65;
    private static final int VERSION = 2;
    private static final int HEADER_BYTES = 12;
    private static final byte[] INFO = "SelfFramed mic pairing v2".getBytes(StandardCharsets.US_ASCII);

    private static ECParameterSpec params;

    private MicPairing() {
    }

    /** A pairing packet as received: type, pairing id and body. */
    public static final class Packet {
        public final int type;
        public final byte[] pairingId;
        public final byte[] body;

        Packet(int type, byte[] pairingId, byte[] body) {
            this.type = type;
            this.pairingId = pairingId;
            this.body = body;
        }
    }

    /** Result of {@link #parseResult}: status and device id, only if the tag proves the PC has the same keys. */
    public static final class Result {
        public final int status;
        public final int device;

        Result(int status, int device) {
            this.status = status;
            this.device = device;
        }
    }

    public static synchronized ECParameterSpec curve() throws GeneralSecurityException {
        if (params == null) {
            params = ((ECPublicKey) newKeyPair().getPublic()).getParams();
        }
        return params;
    }

    public static KeyPair newKeyPair() throws GeneralSecurityException {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        return kpg.generateKeyPair();
    }

    /** For tests: a key pair from a fixed private scalar. */
    public static KeyPair keyPairFromScalar(BigInteger d) throws GeneralSecurityException {
        KeyFactory kf = KeyFactory.getInstance("EC");
        ECParameterSpec p = curve();
        PrivateKey priv = kf.generatePrivate(new ECPrivateKeySpec(d, p));
        ECPoint q = multiply(p, d);
        return new KeyPair(kf.generatePublic(new ECPublicKeySpec(q, p)), priv);
    }

    /** Uncompressed point: 0x04, X (32 bytes), Y (32 bytes). */
    public static byte[] publicBytes(ECPublicKey key) {
        byte[] out = new byte[PUBLIC_KEY_BYTES];
        out[0] = 4;
        copyUnsigned(key.getW().getAffineX(), out, 1);
        copyUnsigned(key.getW().getAffineY(), out, 33);
        return out;
    }

    public static ECPublicKey publicKey(byte[] encoded) throws GeneralSecurityException {
        if (encoded.length != PUBLIC_KEY_BYTES || encoded[0] != 4) {
            throw new GeneralSecurityException("bad public key");
        }
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(encoded, 1, 33));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(encoded, 33, 65));
        return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), curve()));
    }

    /** {audio key, confirm key}: ECDH, then HKDF-SHA256(salt = pairing id, info = INFO + phone pub + PC pub). */
    public static byte[][] derive(PrivateKey own, byte[] peerPub, byte[] pairingId, byte[] phonePub, byte[] pcPub)
            throws GeneralSecurityException {
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(own);
        ka.doPhase(publicKey(peerPub), true);
        byte[] shared = ka.generateSecret();
        byte[] prk = hmac(pairingId, shared);
        byte[] info = concat(INFO, phonePub, pcPub, new byte[] {1});
        byte[] okm = hmac(prk, info);
        return new byte[][] {Arrays.copyOfRange(okm, 0, 16), Arrays.copyOfRange(okm, 16, 32)};
    }

    /** The 6-digit code both screens show, e.g. "482 193". */
    public static String sas(byte[] phonePub, byte[] pcPub) throws GeneralSecurityException {
        byte[] h = MessageDigest.getInstance("SHA-256").digest(concat("SAS".getBytes(StandardCharsets.US_ASCII), phonePub, pcPub));
        long n = (ByteBuffer.wrap(h, 0, 4).getInt() & 0xFFFFFFFFL) % 1000000;
        return String.format(java.util.Locale.ROOT, "%03d %03d", n / 1000, n % 1000);
    }

    public static byte[] resultTag(byte[] confirmKey, byte[] pairingId, int status, int device)
            throws GeneralSecurityException {
        byte[] msg = concat("SP-RESULT".getBytes(StandardCharsets.US_ASCII), pairingId, new byte[] {(byte) status},
                ByteBuffer.allocate(4).putInt(device).array());
        return Arrays.copyOf(hmac(confirmKey, msg), 16);
    }

    public static byte[] hello(byte[] pairingId, byte[] phonePub, String name) throws GeneralSecurityException {
        byte[] raw = name.getBytes(StandardCharsets.UTF_8);
        if (raw.length > 60) {
            raw = Arrays.copyOf(raw, 60);
        }
        byte[] commit = MessageDigest.getInstance("SHA-256").digest(phonePub);
        return packet(HELLO, pairingId, concat(commit, new byte[] {(byte) raw.length}, raw));
    }

    public static byte[] reveal(byte[] pairingId, byte[] phonePub) {
        return packet(REVEAL, pairingId, phonePub);
    }

    private static byte[] packet(int type, byte[] pairingId, byte[] body) {
        return concat(new byte[] {'S', 'P', VERSION, (byte) type}, pairingId, body);
    }

    /** A pairing packet, or null. */
    public static Packet parse(byte[] data, int length) {
        if (length < HEADER_BYTES || data[0] != 'S' || data[1] != 'P' || data[2] != VERSION) {
            return null;
        }
        return new Packet(data[3] & 0xFF, Arrays.copyOfRange(data, 4, 12), Arrays.copyOfRange(data, 12, length));
    }

    /** The decision in a RESULT body, or null if it is malformed or its tag is wrong. */
    public static Result parseResult(byte[] confirmKey, byte[] pairingId, byte[] body) throws GeneralSecurityException {
        if (body.length != 21) {
            return null;
        }
        int status = body[0] & 0xFF;
        int device = ByteBuffer.wrap(body, 1, 4).order(ByteOrder.BIG_ENDIAN).getInt();
        byte[] expected = resultTag(confirmKey, pairingId, status, device);
        return MessageDigest.isEqual(expected, Arrays.copyOfRange(body, 5, 21)) ? new Result(status, device) : null;
    }

    private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) {
            n += p.length;
        }
        byte[] out = new byte[n];
        int at = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length;
        }
        return out;
    }

    private static void copyUnsigned(BigInteger v, byte[] out, int offset) {
        byte[] b = v.toByteArray();
        int start = Math.max(0, b.length - 32);
        int len = b.length - start;
        System.arraycopy(b, start, out, offset + 32 - len, len);
    }

    // Point multiplication for keyPairFromScalar (tests only; plain double-and-add on P-256)
    private static ECPoint multiply(ECParameterSpec p, BigInteger k) {
        BigInteger prime = ((java.security.spec.ECFieldFp) p.getCurve().getField()).getP();
        BigInteger a = p.getCurve().getA();
        ECPoint result = ECPoint.POINT_INFINITY;
        ECPoint addend = p.getGenerator();
        for (int i = 0; i < k.bitLength(); i++) {
            if (k.testBit(i)) {
                result = add(result, addend, prime, a);
            }
            addend = add(addend, addend, prime, a);
        }
        return result;
    }

    private static ECPoint add(ECPoint p1, ECPoint p2, BigInteger prime, BigInteger a) {
        if (p1.equals(ECPoint.POINT_INFINITY)) {
            return p2;
        }
        if (p2.equals(ECPoint.POINT_INFINITY)) {
            return p1;
        }
        BigInteger x1 = p1.getAffineX(), y1 = p1.getAffineY(), x2 = p2.getAffineX(), y2 = p2.getAffineY();
        BigInteger lambda;
        if (x1.equals(x2)) {
            if (!y1.equals(y2) || y1.signum() == 0) {
                return ECPoint.POINT_INFINITY;
            }
            lambda = x1.pow(2).multiply(BigInteger.valueOf(3)).add(a).multiply(y1.shiftLeft(1).modInverse(prime)).mod(prime);
        } else {
            lambda = y2.subtract(y1).multiply(x2.subtract(x1).modInverse(prime)).mod(prime);
        }
        BigInteger x3 = lambda.pow(2).subtract(x1).subtract(x2).mod(prime);
        BigInteger y3 = lambda.multiply(x1.subtract(x3)).subtract(y1).mod(prime);
        return new ECPoint(x3, y3);
    }
}
