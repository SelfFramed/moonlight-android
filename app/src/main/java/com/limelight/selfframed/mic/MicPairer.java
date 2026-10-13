package com.limelight.selfframed.mic;

import com.limelight.LimeLog;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;

/**
 * Runs the pairing with the PC receiver on its own thread (protocol: {@link MicPairing}): HELLO until the PC's
 * key arrives, then the code for the screen, then REVEAL until the PC owner decided. Callbacks come from the
 * pairing thread.
 */
final class MicPairer {
    interface Listener {
        /** Show this code; the PC shows the same one in its question. */
        void onCode(String code);

        void onPaired(int device, byte[] audioKey);

        /** Not paired; reason for the notice. */
        void onFailed(String reason, boolean rejected);
    }

    private static final int KEY_TIMEOUT_MS = 10_000;
    private static final int DECISION_TIMEOUT_MS = 120_000;

    private final String host;
    private final String name;
    private final Listener listener;
    private volatile boolean cancelled;
    private Thread thread;

    MicPairer(String host, String name, Listener listener) {
        this.host = host;
        this.name = name;
        this.listener = listener;
    }

    void start() {
        thread = new Thread(this::run, "SfMicPair");
        thread.start();
    }

    void cancel() {
        cancelled = true;
    }

    private void run() {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(InetAddress.getByName(host), MicPacket.PORT);
            socket.setSoTimeout(1000);
            KeyPair pair = MicPairing.newKeyPair();
            byte[] phonePub = MicPairing.publicBytes((ECPublicKey) pair.getPublic());
            byte[] pid = new byte[8];
            new SecureRandom().nextBytes(pid);
            byte[] hello = MicPairing.hello(pid, phonePub, name);
            byte[] buffer = new byte[2048];

            byte[] pcPub = null;
            long deadline = System.currentTimeMillis() + KEY_TIMEOUT_MS;
            while (pcPub == null) {
                if (cancelled) {
                    return;
                }
                if (System.currentTimeMillis() > deadline) {
                    listener.onFailed("no answer from the PC", false);
                    return;
                }
                socket.send(new DatagramPacket(hello, hello.length));
                MicPairing.Packet p = receive(socket, buffer);
                if (p != null && p.type == MicPairing.KEY && Arrays.equals(p.pairingId, pid)
                        && p.body.length == MicPairing.PUBLIC_KEY_BYTES) {
                    pcPub = p.body;
                }
            }
            byte[][] keys = MicPairing.derive(pair.getPrivate(), pcPub, pid, phonePub, pcPub);
            listener.onCode(MicPairing.sas(phonePub, pcPub));

            byte[] reveal = MicPairing.reveal(pid, phonePub);
            deadline = System.currentTimeMillis() + DECISION_TIMEOUT_MS;
            while (!cancelled && System.currentTimeMillis() < deadline) {
                socket.send(new DatagramPacket(reveal, reveal.length));
                MicPairing.Packet p = receive(socket, buffer);
                if (p == null || p.type != MicPairing.RESULT || !Arrays.equals(p.pairingId, pid)) {
                    continue;
                }
                MicPairing.Result r = MicPairing.parseResult(keys[1], pid, p.body);
                if (r == null || r.status == MicPairing.PENDING) {
                    continue;
                }
                if (r.status == MicPairing.ACCEPTED) {
                    listener.onPaired(r.device, keys[0]);
                } else {
                    listener.onFailed(r.status == MicPairing.REJECTED ? "rejected on the PC" : "the PC is busy with another pairing",
                            r.status == MicPairing.REJECTED);
                }
                return;
            }
            if (!cancelled) {
                listener.onFailed("no decision on the PC", false);
            }
        } catch (Exception e) {
            LimeLog.warning("SelfFramed mic: pairing failed: " + e);
            if (!cancelled) {
                listener.onFailed(e.getMessage() != null ? e.getMessage() : e.toString(), false);
            }
        }
    }

    private static MicPairing.Packet receive(DatagramSocket socket, byte[] buffer) throws java.io.IOException {
        DatagramPacket in = new DatagramPacket(buffer, buffer.length);
        try {
            socket.receive(in);
        } catch (SocketTimeoutException e) {
            return null;
        }
        return MicPairing.parse(in.getData(), in.getLength());
    }
}
