package se.cruzi.tvhem;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

/** Soft, friendly spa music for the screensaver, made on the box (no files, no internet):
 *  a warm major-key pad and slow music-box notes from a pentatonic scale, with a little echo, at a low volume. */
public class Ambient {
    private static Thread th;
    private static volatile boolean run;
    private static final int SR = 32000;
    // bright, warm chords in the middle register: Cmaj9, Am7, Fmaj7, G6 (sus-ish)
    private static final double[][] CH = {
        {261.63, 329.63, 392.00, 493.88},
        {220.00, 261.63, 329.63, 392.00},
        {174.61, 220.00, 261.63, 329.63},
        {196.00, 246.94, 293.66, 329.63}};
    // C major pentatonic for the music-box melody (C5 D5 E5 G5 A5 C6 D6)
    private static final double[] MEL = {523.25, 587.33, 659.25, 783.99, 880.00, 1046.50, 1174.66};

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
        final int N = 4, BUF = 1024, V = 6;
        double[] f = CH[0].clone(), ph = new double[N];
        double[] lr = {0.05, 0.07, 0.045, 0.06};
        // music-box voices: frequency, phase, amplitude
        double[] bf = new double[V], bp = new double[V], ba = new double[V];
        int bv = 0;
        float[] echo = new float[(int) (SR * 0.38)];
        int ei = 0;
        double master = 0, lp = 0;
        long n = 0, nextNote = SR * 2;
        int chord = 0, last = 2;
        java.util.Random rnd = new java.util.Random();
        short[] out = new short[BUF];
        final double decay = Math.exp(-1.0 / (SR * 1.6));   // a note rings for about 2 s
        while (true) {
            boolean on = run;
            for (int i = 0; i < BUF; i++, n++) {
                double tt = n / (double) SR;
                if (n % (SR * 12L) == 0 && n > 0) chord = (chord + 1) % CH.length;
                // warm pad
                double pad = 0;
                for (int k = 0; k < N; k++) {
                    f[k] += (CH[chord][k] - f[k]) * 0.00006;
                    ph[k] += 2 * Math.PI * f[k] / SR; if (ph[k] > 2 * Math.PI) ph[k] -= 2 * Math.PI;
                    pad += Math.sin(ph[k]) * (0.6 + 0.4 * Math.sin(2 * Math.PI * lr[k] * tt + k * 1.7));
                }
                pad /= N;
                lp += (pad - lp) * 0.08;                            // round off the pad so it is soft, not buzzy
                // a new music-box note now and then, stepping gently through the scale
                if (n >= nextNote) {
                    int step = rnd.nextInt(5) - 2; if (step == 0) step = 1;
                    last = Math.max(0, Math.min(MEL.length - 1, last + step));
                    bf[bv] = MEL[last]; bp[bv] = 0; ba[bv] = 0.55 + 0.25 * rnd.nextDouble();
                    bv = (bv + 1) % V;
                    nextNote = n + (long) (SR * (0.9 + rnd.nextDouble() * 1.6));
                }
                double bell = 0;
                for (int k = 0; k < V; k++) {
                    if (ba[k] < 0.0005) continue;
                    bp[k] += 2 * Math.PI * bf[k] / SR;
                    bell += (Math.sin(bp[k]) + 0.25 * Math.sin(2 * bp[k]) + 0.08 * Math.sin(3 * bp[k])) * ba[k];
                    ba[k] *= decay;
                }
                double s = lp * 0.55 + bell * 0.28;
                double e = echo[ei];
                double y = s + e * 0.35;
                echo[ei] = (float) (s + e * 0.45); ei = (ei + 1) % echo.length;
                master += ((on ? 1 : 0) - master) * (on ? 0.000008 : 0.00004); // ~4 s fade in, ~1 s fade out
                out[i] = (short) Math.max(-32767, Math.min(32767, y * master * 0.12 * 32767));
            }
            try { t.write(out, 0, BUF); } catch (Exception e) { break; }
            if (!on && master < 0.002) break;
        }
        try { t.stop(); t.release(); } catch (Exception ignored) {}
    }
}
