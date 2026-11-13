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
            if (ALLOW.equals(intent.getIdentifier()) || hemAsked()) return true;
            ComponentName c = intent.getComponent();
            boolean home = intent.hasCategory(Intent.CATEGORY_HOME);
            boolean startPage = Intent.ACTION_MAIN.equals(intent.getAction()) && c != null && c.getClassName().endsWith(".MainActivity");
            if (home || startPage) {
                Log.d(TAG, "stoppade Telias startsida (" + intent + ")");
                goHome();
                return false;
            }
        } catch (Throwable t) {
            Log.d(TAG, "fel " + t);
        }
        return true;
    }

    /** Hem writes a short "Telia allowed until" time to global settings just before it opens a channel. */
    private boolean hemAsked() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"settings", "get", "global", "hem_telia_until"});
            String l = new BufferedReader(new InputStreamReader(p.getInputStream())).readLine();
            p.waitFor();
            return l != null && l.trim().matches("\\d+") && Long.parseLong(l.trim()) > System.currentTimeMillis();
        } catch (Throwable t) { return false; }
    }

    @Override public boolean activityResuming(String pkg) { return true; }
    @Override public boolean appCrashed(String processName, int pid, String shortMsg, String longMsg, long timeMillis, String stackTrace) { return true; }
    @Override public int appEarlyNotResponding(String processName, int pid, String annotation) { return 0; }
    @Override public int appNotResponding(String processName, int pid, String processStats) { return 0; }
    @Override public int systemNotResponding(String msg) { return -1; }
}
