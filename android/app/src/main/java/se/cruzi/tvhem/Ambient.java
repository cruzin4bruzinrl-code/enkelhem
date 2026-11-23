package se.cruzi.tvhem;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

/** Soft spa music for the screensaver, made on the box (no files, no internet): slow gliding chords, gentle waves and a little echo, at a low volume. */
public class Ambient {
    private static Thread th;
    private static volatile boolean run;
    private static final int SR = 32000;
    // Am9 – Fmaj7 – Cmaj7 – G6, low and wide
    private static final double[][] CH = {
        {110.00, 164.81, 246.94, 261.63, 329.63},
        { 87.31, 130.81, 220.00, 261.63, 329.63},
        {130.81, 196.00, 246.94, 329.63, 392.00},
        { 98.00, 146.83, 246.94, 293.66, 329.63}};

    public static boolean enabled(Context c) { return c.getSharedPreferences("hem", Context.MODE_PRIVATE).getBoolean("saverMusic", true); }

    public static synchronized void start(Context c) {
        if (run || !enabled(c)) return;
        try { if (((AudioManager) c.getSystemService(Context.AUDIO_SERVICE)).isMusicActive()) return; } catch (Exception ignored) {}
        run = true;
        th = new Thread(Ambient::loop, "ambient");
        th.start();
    }

    public static synchronized void stop() { run = false; }

    private static void loop() {
        int min = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack t;
        try {
            t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(SR).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(Math.max(min, 8192) * 2).build();
            t.play();
        } catch (Exception e) { run = false; return; }
        final int N = 5, BUF = 1024;
        double[] f = CH[0].clone(), ph = new double[N], lfo = new double[N];
        double[] lr = {0.031, 0.047, 0.023, 0.039, 0.053};
        float[] echo = new float[(int) (SR * 0.42)];
        int ei = 0;
        double brown = 0, master = 0, waveT = 0;
        long n = 0;
        java.util.Random rnd = new java.util.Random();
        short[] out = new short[BUF];
        int chord = 0;
        while (true) {
            boolean on = run;
            for (int i = 0; i < BUF; i++, n++) {
                double tt = n / (double) SR;
                if (n % (SR * 14L) == 0 && n > 0) chord = (chord + 1) % CH.length;
                double s = 0;
                for (int k = 0; k < N; k++) {
                    f[k] += (CH[chord][k] - f[k]) * 0.00004;                 // slow glide to the next chord
                    ph[k] += 2 * Math.PI * f[k] / SR; if (ph[k] > 2 * Math.PI) ph[k] -= 2 * Math.PI;
                    lfo[k] = 0.55 + 0.45 * Math.sin(2 * Math.PI * lr[k] * tt + k);
                    s += (Math.sin(ph[k]) + 0.18 * Math.sin(2 * ph[k])) * lfo[k] * (k == 0 ? 0.9 : 0.55);
                }
                s /= N;
                brown += (rnd.nextGaussian() * 0.5 - brown) * 0.018;      // soft surf
                waveT += 1.0 / SR;
                double swell = Math.pow(0.5 - 0.5 * Math.cos(2 * Math.PI * waveT / 9.5), 2);
                s += brown * 0.9 * swell;
                double e = echo[ei];
                double y = s + e * 0.32;
                echo[ei] = (float) (s + e * 0.5); ei = (ei + 1) % echo.length;
                master += ((on ? 1 : 0) - master) * (on ? 0.000008 : 0.00004); // ~4 s fade in, ~1 s fade out
                out[i] = (short) Math.max(-32767, Math.min(32767, y * master * 0.16 * 32767));
            }
            try { t.write(out, 0, BUF); } catch (Exception e) { break; }
            if (!on && master < 0.002) break;
        }
        try { t.stop(); t.release(); } catch (Exception ignored) {}
    }
}
