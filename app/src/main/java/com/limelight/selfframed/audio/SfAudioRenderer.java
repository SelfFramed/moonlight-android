package com.limelight.selfframed.audio;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.audiofx.AudioEffect;
import android.os.Build;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.nvstream.av.audio.AudioRenderer;
import com.limelight.nvstream.jni.MoonBridge;

import java.util.Locale;

/**
 * Audio renderer with a jitter buffer (docs/selfframed/ARCHITEKTUR.md). Same AudioTrack setup as
 * upstream's AndroidAudioRenderer, but playback starts only once the target delay is buffered,
 * the target grows after dropouts, clock drift is corrected with crossfades, and the decoder
 * thread runs at audio priority. Falls back to upstream's plain writes if anything goes wrong.
 */
final class SfAudioRenderer implements AudioRenderer {
    private final Context context;
    private final boolean enableAudioFx;
    private final int startTargetMs;
    private final boolean adaptive;

    private AudioTrack track;
    private JitterBuffer buffer;
    private int sampleRate;
    private boolean lowLatency;
    private boolean priorityRaised;
    private boolean failed;

    SfAudioRenderer(Context context, boolean enableAudioFx, int startTargetMs, boolean adaptive) {
        this.context = context;
        this.enableAudioFx = enableAudioFx;
        this.startTargetMs = startTargetMs;
        this.adaptive = adaptive;
    }

    @Override
    public int setup(MoonBridge.AudioConfiguration audioConfiguration, int sampleRate, int samplesPerFrame) {
        int channels = audioConfiguration.channelCount;
        int channelConfig = channelMask(channels);
        if (channelConfig == 0) {
            LimeLog.severe("SelfFramed audio: unhandled channel count " + channels);
            return -1;
        }
        this.sampleRate = sampleRate;

        // Growing the buffer during the stream needs setBufferSizeInFrames (Android 7)
        boolean canResize = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N;
        boolean adapt = adaptive && canResize;
        JitterPolicy policy = new JitterPolicy(sampleRate, samplesPerFrame, startTargetMs,
                adapt ? SfAudio.MAX_TARGET_MS : startTargetMs, adapt);
        int capacityFrames = canResize ? policy.maxTargetFrames() + 4 * samplesPerFrame : policy.targetFrames();
        int bufferBytes = capacityFrames * channels * 2;

        // Like upstream: the low latency path only when the output runs at the stream's rate and
        // no audio effects are wanted (they need the normal path)
        boolean tryLowLatency = !enableAudioFx &&
                AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC) == sampleRate;
        boolean[] modes = tryLowLatency ? new boolean[] {true, false} : new boolean[] {false};
        for (boolean ll : modes) {
            AudioTrack t = null;
            try {
                t = createTrack(channelConfig, sampleRate, bufferBytes, ll);
                if (t.getState() != AudioTrack.STATE_INITIALIZED) {
                    throw new IllegalStateException("not initialized");
                }
                track = t;
                lowLatency = ll;
                break;
            } catch (Exception e) {
                LimeLog.warning("SelfFramed audio: track (low latency " + ll + ") failed: " + e);
                if (t != null) {
                    t.release();
                }
            }
        }
        if (track == null) {
            return -2;
        }

        int capacity = canResize ? track.getBufferCapacityInFrames() : capacityFrames;
        buffer = new JitterBuffer(new AudioTrackSink(track, channels, capacity), policy, channels);
        SfAudio.setCurrent(this);
        LimeLog.info("SelfFramed audio: target " + policy.targetMs() + " ms" + (adapt ? " (adaptive)" : "") +
                ", capacity " + capacity + " frames, low latency " + lowLatency +
                ", native rate " + AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC));
        return 0;
    }

    private static int channelMask(int channels) {
        switch (channels) {
            case 2:
                return AudioFormat.CHANNEL_OUT_STEREO;
            case 4:
                return AudioFormat.CHANNEL_OUT_QUAD;
            case 6:
                return AudioFormat.CHANNEL_OUT_5POINT1;
            case 8:
                // AudioFormat.CHANNEL_OUT_7POINT1_SURROUND, hardcoded like upstream for Lollipop
                return 0x000018fc;
            default:
                return 0;
        }
    }

    private static AudioTrack createTrack(int channelConfig, int sampleRate, int bufferBytes, boolean lowLatency) {
        AudioAttributes.Builder attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME);
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .build();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O && lowLatency) {
            attributes.setFlags(AudioAttributes.FLAG_LOW_LATENCY);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioTrack.Builder builder = new AudioTrack.Builder()
                    .setAudioFormat(format)
                    .setAudioAttributes(attributes.build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(bufferBytes);
            if (lowLatency) {
                builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
            }
            return builder.build();
        }
        return new AudioTrack(attributes.build(), format, bufferBytes, AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE);
    }

    @Override
    public void playDecodedAudio(short[] audioData) {
        if (!priorityRaised) {
            priorityRaised = true;
            raisePriority();
        }
        if (!failed) {
            try {
                int pendingFrames = MoonBridge.getPendingAudioDuration() * sampleRate / 1000;
                buffer.onPacket(audioData, pendingFrames, System.nanoTime());
                return;
            } catch (RuntimeException e) {
                // An exception must never reach the JNI caller; continue like upstream
                failed = true;
                LimeLog.warning("SelfFramed audio: falling back to plain playback: " + e);
            }
        }
        playPlain(audioData);
    }

    /** Upstream's behaviour (AndroidAudioRenderer): write unless 40 ms are already waiting. */
    private void playPlain(short[] audioData) {
        try {
            if (track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                track.play();
            }
            if (MoonBridge.getPendingAudioDuration() < 40) {
                track.write(audioData, 0, audioData.length);
            }
        } catch (RuntimeException ignored) {
        }
    }

    /** The decoder thread feeds the output; at normal priority it can be late on a busy phone. */
    private static void raisePriority() {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        } catch (RuntimeException e) {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
            } catch (RuntimeException ignored) {
            }
        }
    }

    @Override
    public void start() {
        if (enableAudioFx && track != null) {
            // Open an audio effect control session to allow equalizers to apply audio effects
            Intent i = new Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
            i.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, track.getAudioSessionId());
            i.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.getPackageName());
            i.putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_GAME);
            context.sendBroadcast(i);
        }
    }

    @Override
    public void stop() {
        if (enableAudioFx && track != null) {
            Intent i = new Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
            i.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, track.getAudioSessionId());
            i.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.getPackageName());
            context.sendBroadcast(i);
        }
    }

    @Override
    public void cleanup() {
        SfAudio.clearCurrent(this);
        JitterBuffer b = buffer;
        if (b != null) {
            LimeLog.info("SelfFramed audio: dropouts " + b.dropouts() + ", shortened " + b.shortened() +
                    ", lengthened " + b.lengthened() + ", final target " + b.targetMs() + " ms" +
                    (failed ? ", fell back to plain playback" : ""));
            if (adaptive) {
                SfAudio.rememberSession(context, b.targetMs(), b.dropouts());
            }
        }
        if (track != null) {
            try {
                // Immediately drop all pending data
                track.pause();
                track.flush();
            } catch (RuntimeException ignored) {
            }
            track.release();
        }
    }

    /** One line for the performance overlay; any thread. */
    String statsLine(Context c) {
        JitterBuffer b = buffer;
        AudioTrack t = track;
        if (b == null || t == null) {
            return null;
        }
        return c.getString(R.string.sf_audio_perf_line, b.delayMs(), b.targetMs(), b.dropouts(),
                b.shortened(), b.lengthened(), describeOutput(c, t));
    }

    private String describeOutput(Context c, AudioTrack t) {
        String device = c.getString(R.string.sf_audio_out_unknown);
        boolean fast = lowLatency;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                AudioDeviceInfo d = t.getRoutedDevice();
                if (d != null) {
                    device = c.getString(deviceLabel(d.getType()));
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                fast = t.getPerformanceMode() == AudioTrack.PERFORMANCE_MODE_LOW_LATENCY;
            }
        } catch (RuntimeException ignored) {
            // The track may have been released meanwhile
        }
        int rate = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC);
        String khz = rate % 1000 == 0 ? String.valueOf(rate / 1000) : String.format(Locale.ROOT, "%.1f", rate / 1000.0);
        return c.getString(R.string.sf_audio_out, device, khz,
                c.getString(fast ? R.string.sf_audio_mode_fast : R.string.sf_audio_mode_normal));
    }

    private static int deviceLabel(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE:
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE:
                return R.string.sf_audio_out_speaker;
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case AudioDeviceInfo.TYPE_LINE_ANALOG:
            case AudioDeviceInfo.TYPE_AUX_LINE:
                return R.string.sf_audio_out_wired;
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
            case AudioDeviceInfo.TYPE_BLE_SPEAKER:
            case AudioDeviceInfo.TYPE_BLE_BROADCAST:
                return R.string.sf_audio_out_bluetooth;
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_USB_ACCESSORY:
            case AudioDeviceInfo.TYPE_DOCK:
                return R.string.sf_audio_out_usb;
            case AudioDeviceInfo.TYPE_HDMI:
            case AudioDeviceInfo.TYPE_HDMI_ARC:
            case AudioDeviceInfo.TYPE_HDMI_EARC:
                return R.string.sf_audio_out_hdmi;
            default:
                return R.string.sf_audio_out_other;
        }
    }
}
