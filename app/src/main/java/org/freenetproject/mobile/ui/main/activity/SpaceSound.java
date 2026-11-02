package org.freenetproject.mobile.ui.main.activity;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/**
 * Synthesizes a short space/sci-fi sound using AudioTrack — no audio file needed.
 * The sound is a frequency sweep with tremolo modulation followed by a descending beep.
 */
public final class SpaceSound {
    private static final int SAMPLE_RATE = 44100;

    public static void play() {
        new Thread(() -> {
            short[] samples = buildSamples();
            AudioTrack track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(samples.length * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build();
            track.write(samples, 0, samples.length);
            track.play();
            try {
                Thread.sleep((samples.length * 1000L) / SAMPLE_RATE + 200);
            } catch (InterruptedException ignored) {
            }
            track.stop();
            track.release();
        }, "freenet-space-sound").start();
    }

    private static short[] buildSamples() {
        // Phase 1: sweep 180 Hz → 900 Hz over 1.2 s with tremolo
        // Phase 2: three descending beeps (500 ms total)
        int sweep = (int) (1.2 * SAMPLE_RATE);
        int beeps = (int) (0.5 * SAMPLE_RATE);
        short[] out = new short[sweep + beeps];

        double phase = 0;
        for (int i = 0; i < sweep; i++) {
            double t = (double) i / SAMPLE_RATE;
            double freq = 180 + (900 - 180) * ((double) i / sweep);
            double tremolo = 0.6 + 0.4 * Math.sin(2 * Math.PI * 7 * t);
            double env = i < 2000 ? i / 2000.0 : (i > sweep - 3000 ? (sweep - i) / 3000.0 : 1.0);
            phase += 2 * Math.PI * freq / SAMPLE_RATE;
            out[i] = (short) (Math.sin(phase) * tremolo * env * 28000);
        }

        // Three beeps: 900 Hz, 600 Hz, 300 Hz, each ~160 ms with gaps
        double[] beepFreqs = {900, 600, 300};
        int beepLen = (int) (0.12 * SAMPLE_RATE);
        int gap = (int) (0.046 * SAMPLE_RATE);
        int pos = sweep;
        double bp = 0;
        for (double f : beepFreqs) {
            bp = 0;
            for (int j = 0; j < beepLen && pos < out.length; j++, pos++) {
                double env = j < 500 ? j / 500.0 : (j > beepLen - 500 ? (beepLen - j) / 500.0 : 1.0);
                bp += 2 * Math.PI * f / SAMPLE_RATE;
                out[pos] = (short) (Math.sin(bp) * env * 26000);
            }
            // silence gap
            for (int j = 0; j < gap && pos < out.length; j++, pos++) {
                out[pos] = 0;
            }
        }
        return out;
    }
}
