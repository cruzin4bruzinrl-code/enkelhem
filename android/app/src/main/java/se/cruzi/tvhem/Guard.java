package se.cruzi.tvhem;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Looper;
import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

/**
 * "Vakten": runs from adb as the shell user (app_process), registers as the system's activity controller and
 * refuses every start of Telia's start page that Hem did not ask for. Hem is started instead, so Telia's
 * launcher is never drawn, not even for a frame. Telia is still allowed when Hem opens a channel
 * (those intents carry the identifier "hem-allow").
 *
 *   CLASSPATH=$(pm path se.cruzi.tvhem | cut -d: -f2) app_process / se.cruzi.tvhem.Guard
 */
public class Guard extends android.app.IActivityController.Stub {
    static final String TAG = "HemVakt";
    static final String TELIA = "se.telia.androidtv.launcher";
    static final String ALLOW = "hem-allow";
    private volatile boolean hemEnabled = true;
    private volatile long lastHome;

    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        Guard g = new Guard();
        Object am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
        Method set = null;
        for (Method m : am.getClass().getMethods())
            if (m.getName().equals("setActivityController") && m.getParameterTypes().length == 2) set = m;
        if (set == null) { System.out.println("fel: setActivityController saknas"); return; }
        set.invoke(am, g, false);
        g.watchHem();
        Log.d(TAG, "vakten är igång");
        System.out.println("vakten är igång");
        Looper.loop();
    }

    /** Keep track of whether Hem is installed and enabled; if not (e.g. 'tv telia'), let Telia through. */
    private void watchHem() {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Process p = Runtime.getRuntime().exec(new String[]{"pm", "list", "packages", "-e", "se.cruzi.tvhem"});
                    BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                    boolean found = false;
                    String l;
                    while ((l = r.readLine()) != null) if (l.trim().equals("package:se.cruzi.tvhem")) found = true;
                    p.waitFor();
                    hemEnabled = found;
                } catch (Throwable ignored) {}
                try { Thread.sleep(30000); } catch (InterruptedException ignored) {}
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private void goHome() {
        long now = System.currentTimeMillis();
        if (now - lastHome < 800) return;
        lastHome = now;
        new Thread(() -> {
            try {
                Runtime.getRuntime().exec(new String[]{"am", "start", "-n", "se.cruzi.tvhem/.HomeActivity", "-f", "0x10020000"}).waitFor();
            } catch (Throwable ignored) {}
        }).start();
    }

    @Override
    public boolean activityStarting(Intent intent, String pkg) {
        try {
            if (!hemEnabled || !TELIA.equals(pkg) || intent == null) return true;
            if (ALLOW.equals(intent.getIdentifier())) return true;
            ComponentName c = intent.getComponent();
            boolean home = intent.hasCategory(Intent.CATEGORY_HOME);
            // HOME is never Telia's, not even right after a TV4/Axess session.
            if (home) {
                Log.d(TAG, "stoppade Telias startsida via HOME (" + intent + ")");
                clearAsked();
                goHome();
                return false;
            }
            // Telia's start page (however it is opened, also from inside Telia's own player) is only allowed
            // in the first seconds after Hem asked for a channel, while Hem steers through Telia's guide.
            boolean startPage = c != null && c.getClassName().endsWith(".MainActivity");
            if (startPage && !hemRecent()) {
                Log.d(TAG, "stoppade Telias startsida (" + intent + ")");
                clearAsked();
                goHome();
                return false;
            }
        } catch (Throwable t) {
            Log.d(TAG, "fel " + t);
        }
        return true;
    }

    private void clearAsked() {
        try { Runtime.getRuntime().exec(new String[]{"settings", "put", "global", "hem_telia", "0"}); } catch (Throwable t) { }
    }

    /** Hem sets global setting hem_telia to the time (ms) it sent the user into Telia; 0 when back in Hem. */
    private long askedAt() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"settings", "get", "global", "hem_telia"});
            String l = new BufferedReader(new InputStreamReader(p.getInputStream())).readLine();
            p.waitFor();
            if (l == null) return 0;
            l = l.trim();
            if (l.equals("1")) return System.currentTimeMillis();
            if (l.equals("2")) return 1;   // watching a Telia channel: allowed, but not its start page
            return Long.parseLong(l);
        } catch (Throwable t) { return 0; }
    }
    private boolean hemAsked() { return askedAt() > 0; }
    private boolean hemRecent() { long a = askedAt(); return a > 0 && System.currentTimeMillis() - a < 30000; }

    /** Telia's launcher coming back to the front by itself (its old task, without a new start) is refused too. */
    @Override
    public boolean activityResuming(String pkg) {
        try {
            // Only while Hem is steering to a channel may Telia come (back) to the front. Once the channel plays,
            // Telia coming back means its start page under the player (e.g. after HOME): straight to Hem instead.
            if (hemEnabled && TELIA.equals(pkg) && !hemRecent()) {
                Log.d(TAG, "stoppade att Telia kom fram igen");
                clearAsked();
                goHome();
                return false;
            }
        } catch (Throwable t) { Log.d(TAG, "fel " + t); }
        return true;
    }
    @Override public boolean appCrashed(String processName, int pid, String shortMsg, String longMsg, long timeMillis, String stackTrace) { return true; }
    @Override public int appEarlyNotResponding(String processName, int pid, String annotation) { return 0; }
    @Override public int appNotResponding(String processName, int pid, String processStats) { return 0; }
    @Override public int systemNotResponding(String msg) { return -1; }
}
