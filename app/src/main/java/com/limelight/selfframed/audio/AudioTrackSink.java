package com.limelight.selfframed.audio;

import android.media.AudioTrack;
import android.os.Build;

/** {@link PcmSink} on a streaming {@link AudioTrack}. Used from the audio decoder thread only. */
final class AudioTrackSink implements PcmSink {
    private final AudioTrack track;
    private final int channels;
    private final int capacityFrames;
    private long writtenFrames;
    private long playedFrames;
    private int lastHead;

    AudioTrackSink(AudioTrack track, int channels, int capacityFrames) {
        this.track = track;
        this.channels = channels;
        this.capacityFrames = capacityFrames;
    }

    @Override
    public void write(short[] pcm) {
        int offset = 0;
        while (offset < pcm.length) {
            int n = track.write(pcm, offset, pcm.length - offset);
            if (n <= 0) {
                // Error code (track released, dead output): drop the rest of this packet
                return;
            }
            offset += n;
            writtenFrames += n / channels;
        }
    }

    @Override
    public int levelFrames() {
        // The head position is an unsigned 32-bit frame counter; accumulate it so it may wrap
        int head = track.getPlaybackHeadPosition();
        playedFrames += (head - lastHead) & 0xFFFFFFFFL;
        lastHead = head;
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, writtenFrames - playedFrames));
    }

    @Override
    public int underrunCount() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N ? track.getUnderrunCount() : -1;
    }

    @Override
    public int setBufferFrames(int frames) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            int applied = track.setBufferSizeInFrames(Math.min(frames, capacityFrames));
            if (applied > 0) {
                return applied;
            }
        }
        // Android 6 and older: the buffer was created at its final size
        return capacityFrames;
    }

    @Override
    public void play() {
        track.play();
    }
}
