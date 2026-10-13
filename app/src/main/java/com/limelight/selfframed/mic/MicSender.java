package com.limelight.selfframed.mic;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.Build;

import com.limelight.LimeLog;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.security.SecureRandom;

/**
 * Records the phone's microphone and sends it to the PC receiver (tools/sf/mic/receiver.py): 10 ms frames at
 * 48 kHz mono, sealed with {@link MicPacket}, as UDP to port {@link MicPacket#PORT} of the streaming host. The
 * voice-call source turns on Android's echo canceller, so the stream's own sound from the phone's speaker is not
 * sent back. Runs on its own thread at audio priority.
 */
final class MicSender {
    interface Listener {
        /** Called on the sender thread when recording or sending cannot continue. */
        void onMicFailed(String reason);
    }

    private final String host;
    private final byte[] key;
    private final Listener listener;
    private volatile boolean running;
    private Thread thread;

    MicSender(String host, byte[] key, Listener listener) {
        this.host = host;
        this.key = key;
        this.listener = listener;
    }

    void start() {
        running = true;
        thread = new Thread(this::run, "SfMic");
        thread.start();
    }

    /** Stops recording; the last packet tells the PC to close its microphone at once. */
    void stop() {
        running = false;
        Thread t = thread;
        if (t != null) {
            try {
                t.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void run() {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        } catch (RuntimeException ignored) {
        }
        AudioRecord recorder = null;
        DatagramSocket socket = null;
        AcousticEchoCanceler aec = null;
        NoiseSuppressor ns = null;
        AutomaticGainControl agc = null;
        long sent = 0;
        long sendFailures = 0;
        try {
            InetAddress address = InetAddress.getByName(host);
            socket = new DatagramSocket();
            socket.connect(address, MicPacket.PORT);

            int minBytes = AudioRecord.getMinBufferSize(MicPacket.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, MicPacket.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minBytes, MicPacket.SAMPLE_RATE / 5 * 2));
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("microphone not available");
            }
            int audioSession = recorder.getAudioSessionId();
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(audioSession);
                if (aec != null) {
                    aec.setEnabled(true);
                }
            }
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(audioSession);
                if (ns != null) {
                    ns.setEnabled(true);
                }
            }
            if (AutomaticGainControl.isAvailable()) {
                agc = AutomaticGainControl.create(audioSession);
                if (agc != null) {
                    agc.setEnabled(true);
                }
            }

            MicPacket packer = new MicPacket(key, new SecureRandom().nextInt());
            short[] frame = new short[MicPacket.FRAME_SAMPLES];
            recorder.startRecording();
            LimeLog.info("SelfFramed mic: sending to " + address.getHostAddress() + ":" + MicPacket.PORT +
                    " (echo canceller " + (aec != null) + ", noise suppressor " + (ns != null) + ")");
            long seq = 0;
            while (running) {
                int filled = 0;
                while (filled < frame.length && running) {
                    int n = recorder.read(frame, filled, frame.length - filled);
                    if (n < 0) {
                        throw new IllegalStateException("recording failed (" + n + ")");
                    }
                    filled += n;
                }
                if (!running) {
                    break;
                }
                byte[] packet = packer.seal(seq++, System.currentTimeMillis() / 1000, 0, frame, 0);
                try {
                    socket.send(new DatagramPacket(packet, packet.length));
                    sent++;
                } catch (IOException e) {
                    // Network gone for a moment (e.g. Wi-Fi roaming): keep recording, the PC fills silence
                    if (sendFailures++ == 0) {
                        LimeLog.warning("SelfFramed mic: send failed: " + e);
                    }
                }
            }
            java.util.Arrays.fill(frame, (short) 0);
            byte[] end = packer.seal(seq, System.currentTimeMillis() / 1000, MicPacket.FLAG_END, frame, 0);
            try {
                socket.send(new DatagramPacket(end, end.length));
            } catch (IOException ignored) {
            }
        } catch (Exception e) {
            LimeLog.warning("SelfFramed mic: stopped: " + e);
            if (running) {
                listener.onMicFailed(e.getMessage() != null ? e.getMessage() : e.toString());
            }
        } finally {
            if (recorder != null) {
                try {
                    if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                        recorder.stop();
                    }
                } catch (RuntimeException ignored) {
                }
                recorder.release();
            }
            if (aec != null) {
                aec.release();
            }
            if (ns != null) {
                ns.release();
            }
            if (agc != null) {
                agc.release();
            }
            if (socket != null) {
                socket.close();
            }
            LimeLog.info("SelfFramed mic: " + sent + " packets sent, " + sendFailures + " send failures");
        }
    }

    static boolean supportsRuntimePermission() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;
    }
}
