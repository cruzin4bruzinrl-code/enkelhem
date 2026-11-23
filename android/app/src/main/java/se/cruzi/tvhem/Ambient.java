package se.cruzi.tvhem;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

/** Calm music for the screensaver, made on the box (no files, no internet), at a low volume.
 *  0 Speldosa · 1 Lugna ackord · 2 Havsvågor · 3 Regn · 4 Harpa · 5 Vindspel · 6 Mjukt piano · 7 YouTube-livemusik (spelas av SaverActivity) · 8 Ingen musik */
public class Ambient {
    public static final int YT = 7, NONE = 8;
    public static int tuneOf(Context c) { return c.getSharedPreferences("hem", Context.MODE_PRIVATE).getInt("saverTune", 0); }
    public static int volOf(Context c) { return Math.max(1, Math.min(5, c.getSharedPreferences("hem", Context.MODE_PRIVATE).getInt("saverVol", 3))); }
    private static Thread th;
    private static volatile boolean run;
    private static final int SR = 32000;
    // warm major-key chords in the middle register: Cmaj9, Am7, Fmaj7, G6
    private static final double[][] CH = {
        {261.63, 329.63, 392.00, 493.88},
        {220.00, 261.63, 329.63, 392.00},
        {174.61, 220.00, 261.63, 329.63},
        {196.00, 246.94, 293.66, 329.63}};
    // C major pentatonic (C5 D5 E5 G5 A5 C6 D6)
    private static final double[] MEL = {523.25, 587.33, 659.25, 783.99, 880.00, 1046.50, 1174.66};

    private static volatile int tune;
    private static volatile double gain = 0.11;
    private static final double[] VOL = {0, 0.035, 0.065, 0.11, 0.16, 0.23};
    // evens out the tunes (measured offline) so every one is clearly heard at the same volume step; quiet rain and waves need more
    private static final double[] TGAIN = {2.9, 2.7, 6.8, 5.2, 1.7, 4.0, 2.5};
    private static final android.os.Handler H = new android.os.Handler(android.os.Looper.getMainLooper());
    private static final Runnable PREVIEW_END = Ambient::stop;

    public static boolean enabled(Context c) { return c.getSharedPreferences("hem", Context.MODE_PRIVATE).getInt("saverTune", 0) != NONE; }

    private static void read(Context c) {
        android.content.SharedPreferences p = c.getSharedPreferences("hem", Context.MODE_PRIVATE);
        tune = p.getInt("saverTune", 0);
        gain = VOL[Math.max(1, Math.min(5, p.getInt("saverVol", 3)))];
    }

    /** Settings: play a few seconds of the chosen music at the chosen volume. */
    public static synchronized void preview(Context c) {
        read(c);
        H.removeCallbacks(PREVIEW_END);
        if (tune >= YT) { stop(); return; }
        if (!run) { run = true; th = new Thread(Ambient::loop, "ambient"); th.start(); }
        H.postDelayed(PREVIEW_END, 8000);
    }

    /** Used when the YouTube stream cannot be played: the music box instead. */
    public static synchronized void startFallback(Context c) {
        H.removeCallbacks(PREVIEW_END); read(c); tune = 0;
        if (run) return; run = true; th = new Thread(Ambient::loop, "ambient"); th.start();
    }

    public static synchronized void start(Context c) {
        H.removeCallbacks(PREVIEW_END);
        read(c);
        if (run || tune >= YT) return;
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
        final int N = 4, BUF = 1024, V = 10;
        double[] f = CH[0].clone(), ph = new double[N];
        double[] lr = {0.05, 0.07, 0.045, 0.06};
        // plucked/bell voices: frequency, phase, amplitude, decay per sample, timbre kind
        double[] bf = new double[V], bp = new double[V], ba = new double[V], bd = new double[V];
        int[] bk = new int[V];
        int bv = 0;
        float[] echo = new float[(int) (SR * 0.38)];
        int ei = 0;
        double master = 0, lp = 0, surf = 0, rain = 0, drop = 0, dropF = 2000, dropP = 0;
        long n = 0, nextNote = SR * 2;
        int chord = 0, last = 2, arp = 0;
        java.util.Random rnd = new java.util.Random();
        short[] out = new short[BUF];
        while (true) {
            boolean on = run;
            int tn = tune;
            for (int i = 0; i < BUF; i++, n++) {
                double tt = n / (double) SR;
                if (n % (SR * 12L) == 0 && n > 0) chord = (chord + 1) % CH.length;
                double pad = 0;
                for (int k = 0; k < N; k++) {
                    f[k] += (CH[chord][k] - f[k]) * 0.00006;
                    ph[k] += 2 * Math.PI * f[k] / SR; if (ph[k] > 2 * Math.PI) ph[k] -= 2 * Math.PI;
                    pad += Math.sin(ph[k]) * (0.6 + 0.4 * Math.sin(2 * Math.PI * lr[k] * tt + k * 1.7));
                }
                pad /= N;
                lp += (pad - lp) * 0.08;

                // new notes for the plucked/bell tunes
                if (n >= nextNote && (tn == 0 || tn == 4 || tn == 5 || tn == 6)) {
                    double fr; double sec; int kind;
                    if (tn == 4) {                                   // harp: slow rising arpeggios over the chord, a rest after each
                        int idx = arp % 6; fr = CH[chord][idx % 4] * (idx < 4 ? 2 : 4); arp++;
                        sec = 2.4; kind = 1; nextNote = n + (long) (SR * (arp % 6 == 0 ? 2.6 : 0.42));
                    } else if (tn == 5) {                            // wind chimes: high, sparse, random
                        fr = MEL[rnd.nextInt(MEL.length)] * 2; sec = 4.5; kind = 2;
                        nextNote = n + (long) (SR * (rnd.nextDouble() < 0.3 ? 0.18 + rnd.nextDouble() * 0.3 : 1.2 + rnd.nextDouble() * 2.8));
                    } else if (tn == 6) {                            // soft piano: chord tones, unhurried
                        fr = CH[chord][rnd.nextInt(4)] * (rnd.nextDouble() < 0.5 ? 1 : 2); sec = 3.0; kind = 3;
                        nextNote = n + (long) (SR * (1.3 + rnd.nextDouble() * 2.2));
                    } else {                                          // music box: gentle steps through the scale
                        int step = rnd.nextInt(5) - 2; if (step == 0) step = 1;
                        last = Math.max(0, Math.min(MEL.length - 1, last + step));
                        fr = MEL[last]; sec = 1.6; kind = 0;
                        nextNote = n + (long) (SR * (0.9 + rnd.nextDouble() * 1.6));
                    }
                    bf[bv] = fr; bp[bv] = 0; ba[bv] = 0.55 + 0.25 * rnd.nextDouble(); bd[bv] = Math.exp(-1.0 / (SR * sec)); bk[bv] = kind;
                    bv = (bv + 1) % V;
                }
                double bell = 0;
                for (int k = 0; k < V; k++) {
                    if (ba[k] < 0.0005) continue;
                    bp[k] += 2 * Math.PI * bf[k] / SR;
                    double p = bp[k], v;
                    switch (bk[k]) {
                        case 1: v = Math.sin(p) + 0.5 * Math.sin(2 * p) + 0.18 * Math.sin(3 * p); break;          // harp
                        case 2: v = Math.sin(p) + 0.35 * Math.sin(2.76 * p) + 0.15 * Math.sin(5.4 * p); break;   // chime
                        case 3: v = Math.sin(p) + 0.4 * Math.sin(2 * p) + 0.15 * Math.sin(3 * p) + 0.06 * Math.sin(4 * p); break; // piano
                        default: v = Math.sin(p) + 0.25 * Math.sin(2 * p) + 0.08 * Math.sin(3 * p);               // music box
                    }
                    bell += v * ba[k];
                    ba[k] *= bd[k];
                }

                double s;
                switch (tn) {
                    case 1: s = lp * 0.8; break;
                    case 2: {
                        surf += (rnd.nextGaussian() - surf) * 0.06;
                        double sw = 0.5 - 0.5 * Math.cos(2 * Math.PI * tt / 8.5);
                        s = surf * 0.55 * sw * sw + lp * 0.18; break;
                    }
                    case 3: {                                         // soft rain: an even hush with tiny drops, and a faint warm chord
                        rain += (rnd.nextGaussian() - rain) * 0.22;
                        if (drop < 0.002 && rnd.nextDouble() < 18.0 / SR) { drop = 0.15 + rnd.nextDouble() * 0.2; dropF = 1400 + rnd.nextDouble() * 2400; }
                        dropP += 2 * Math.PI * dropF / SR; double d = Math.sin(dropP) * drop; drop *= 0.9975;
                        s = rain * 0.16 + d * 0.5 + lp * 0.16; break;
                    }
                    case 4: s = lp * 0.3 + bell * 0.24; break;
                    case 5: s = lp * 0.12 + bell * 0.2; break;
                    case 6: s = lp * 0.22 + bell * 0.3; break;
                    default: s = lp * 0.55 + bell * 0.28;
                }
                double e = echo[ei];
                double y = s + e * 0.35;
                echo[ei] = (float) (s + e * 0.45); ei = (ei + 1) % echo.length;
                master += ((on ? 1 : 0) - master) * (on ? 0.000008 : 0.00004); // ~4 s fade in, ~1 s fade out
                out[i] = (short) Math.max(-32767, Math.min(32767, y * master * gain * TGAIN[Math.min(tn, 6)] * 32767));
            }
            try { t.write(out, 0, BUF); } catch (Exception e) { break; }
            if (!on && master < 0.002) break;
        }
        try { t.stop(); t.release(); } catch (Exception ignored) {}
    }
}
