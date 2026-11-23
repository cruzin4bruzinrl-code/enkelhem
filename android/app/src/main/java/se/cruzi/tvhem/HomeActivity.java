package se.cruzi.tvhem;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Base64;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.Inet4Address;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@SuppressLint({"MissingPermission", "SetJavaScriptEnabled"})
public class HomeActivity extends Activity {
    private static HomeActivity self;

    /** Run JavaScript in the home screen (from the tablet remote). */
    static void runJs(String js) {
        HomeActivity a = self;
        if (a == null) return;
        a.ui.post(() -> { if (a.web != null) a.web.evaluateJavascript(js, null); });
    }

    static void jsKey(String k) { runJs("window.oceanKey&&oceanKey('" + k + "')"); }


    static final String DEFAULT_URL = "https://raw.githubusercontent.com/cruzin4bruzinrl-code/enkelhem/main/tv/index.html";
    private WebView web;
    private EarthView earth;
    private SharedPreferences prefs;
    private File cache;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private BluetoothProfile a2dp;
    private boolean longFired;

    @Override
    protected void onCreate(Bundle b) {
        self = this;
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("hem", MODE_PRIVATE);
        if (!prefs.contains("url")) prefs.edit().putString("url", DEFAULT_URL).apply();
        cache = new File(getFilesDir(), "remote.html");
        saveUrl(getIntent());

        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF010208));
        earth = new EarthView(this);
        web = new WebView(this);
        try { web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false); } catch (Throwable ignored) {}
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.addView(earth, new android.widget.FrameLayout.LayoutParams(-1, -1));
        root.addView(web, new android.widget.FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setBackgroundColor(0x00000000);
        web.addJavascriptInterface(new Bridge(), "OceanBridge");
        web.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage m) {
                android.util.Log.i("HemJs", m.messageLevel() + " " + m.message() + " @" + m.lineNumber());
                return true;
            }
        });

        loadLocal();
        initBluetooth();
        immersive();
    }

    // ---------- design: cached copy from Netlify, else the built-in one ----------
    private void loadLocal() {
        if (cache.exists() && cache.length() > 500) web.loadUrl("file://" + cache.getAbsolutePath());
        else web.loadUrl("file:///android_asset/index.html");
    }

    /** Re-enable our accessibility service if Android switched it off (needs WRITE_SECURE_SETTINGS, granted once via adb). */
    private static long lastRebind;

    private void ensureHomeKey() {
        try {
            if (checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != PackageManager.PERMISSION_GRANTED) return;
            String me = getPackageName() + "/" + HomeKeyService.class.getName();
            String cur = Settings.Secure.getString(getContentResolver(), "enabled_accessibility_services");
            if (cur != null && cur.contains(HomeKeyService.class.getName())) {
                if (HomeKeyService.running() || System.currentTimeMillis() - lastRebind < 15000) return;
                lastRebind = System.currentTimeMillis();
                // listed but not running (happens after an app update): switch it off and on so Android binds it again
                String without = cur.replace(":" + me, "").replace(me + ":", "").replace(me, "")
                    .replace(":" + getPackageName() + "/.HomeKeyService", "").replace(getPackageName() + "/.HomeKeyService:", "").replace(getPackageName() + "/.HomeKeyService", "");
                Settings.Secure.putString(getContentResolver(), "enabled_accessibility_services", without);
                final String back = without.isEmpty() ? me : without + ":" + me;
                ui.postDelayed(() -> {
                    try {
                        Settings.Secure.putString(getContentResolver(), "enabled_accessibility_services", back);
                        Settings.Secure.putString(getContentResolver(), "accessibility_enabled", "1");
                    } catch (Exception ignored) {}
                }, 400);
                return;
            }
            String next = (cur == null || cur.isEmpty()) ? me : cur + ":" + me;
            Settings.Secure.putString(getContentResolver(), "enabled_accessibility_services", next);
            Settings.Secure.putString(getContentResolver(), "accessibility_enabled", "1");
        } catch (Exception ignored) {}
    }

    private void saveUrl(Intent i) {
        if (i == null) return;
        String u = i.getStringExtra("url");
        if (u != null) {
            prefs.edit().putString("url", u.trim()).apply();
            cache.delete();
        }
    }

    private void fetchRemote() {
        final String base = prefs.getString("url", "");
        if (base.isEmpty()) return;
        new Thread(() -> {
            try {
                String u = base + (base.contains("?") ? "&" : "?") + "t=" + System.currentTimeMillis();
                HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(8000);
                c.setUseCaches(false);
                if (c.getResponseCode() != 200) return;
                InputStream in = c.getInputStream();
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                in.close();
                byte[] data = bo.toByteArray();
                String head = new String(data, 0, Math.min(data.length, 400), "UTF-8").toLowerCase();
                if (data.length < 500 || !head.contains("<html")) return;
                if (cache.exists() && Arrays.equals(data, Files.readAllBytes(cache.toPath()))) return;
                File tmp = new File(getFilesDir(), "remote.tmp");
                FileOutputStream fo = new FileOutputStream(tmp);
                fo.write(data);
                fo.close();
                if (!tmp.renameTo(cache)) return;
                ui.post(this::loadLocal);
            } catch (Exception ignored) {}
        }).start();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        runTestJs(intent);
        // HOME pressed while Hem is already open: close whatever is open and show the home screen
        if (intent.getStringExtra("url") == null && intent.getStringExtra("js64") == null && web != null)
            web.evaluateJavascript("try{homePressed()}catch(e){}", null);
        if (intent.getStringExtra("url") != null) {
            saveUrl(intent);
            loadLocal();
            fetchRemote();
        }
    }

    /** Stresstest: adb kan skicka JavaScript (base64 i extra "js64") som körs i launchern; svaret loggas som HemJs. */
    private void runTestJs(Intent i) {
        if (i == null || web == null) return;
        String b = i.getStringExtra("js64");
        if (b == null) return;
        try {
            String js = new String(android.util.Base64.decode(b, android.util.Base64.DEFAULT), "UTF-8");
            web.evaluateJavascript(js, r -> android.util.Log.i("HemJs", "RESULT " + r));
        } catch (Exception e) { android.util.Log.i("HemJs", "RESULT fel " + e); }
    }

    static volatile boolean saverIsOn;

    @Override
    protected void onPause() {
        super.onPause();
        saverIsOn = false; Ambient.stop();
        if (earth != null) earth.onPause();
    }

    private long guardWarned, lastTrim;

    /** Screensaver: ask the TV for 60 Hz (same resolution) so the particles move at 60 frames a second; back to normal after. */
    private void refresh60(boolean on) {
        try {
            android.view.Display d = getWindowManager().getDefaultDisplay();
            android.view.Display.Mode cur = d.getMode();
            int id = 0;
            if (on) for (android.view.Display.Mode m : d.getSupportedModes())
                if (m.getPhysicalWidth() == cur.getPhysicalWidth() && m.getPhysicalHeight() == cur.getPhysicalHeight()
                        && Math.abs(m.getRefreshRate() - 60f) < 1f) id = m.getModeId();
            android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
            if (lp.preferredDisplayModeId != id) {
                lp.preferredDisplayModeId = id;
                getWindow().setAttributes(lp);
                android.util.Log.d("HemTv", "bildfrekvens: " + (id == 0 ? "som vanligt" : "60 Hz") + " (nu " + cur.getRefreshRate() + ")");
            }
        } catch (Exception ignored) {}
    }

    /** Music apps may keep playing behind Hem; everything else (tv, video) is stopped when you leave it. */
    static boolean isMusic(android.content.Context c, String pkg) {
        if (pkg == null) return false;
        if (pkg.matches("(?i).*(spotify|tidal|deezer|soundcloud|youtube\\.music|youtube\\.tvmusic|podcast|pocketcasts|audible|storytel|bookbeat|nextory|sverigesradio|radio|bbc\\.sounds|music|mixcloud|qobuz|plexamp).*")) return true;
        try { return c.getPackageManager().getApplicationInfo(pkg, 0).category == ApplicationInfo.CATEGORY_AUDIO; }
        catch (Exception e) { return false; }
    }

    private static String art64(android.media.MediaMetadata m) {
        try {
            android.graphics.Bitmap b = m.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (b == null) b = m.getBitmap(android.media.MediaMetadata.METADATA_KEY_ART);
            if (b == null) b = m.getBitmap(android.media.MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            if (b == null) return null;
            android.graphics.Bitmap sc = android.graphics.Bitmap.createScaledBitmap(b, 220, Math.max(1, Math.round(220f * b.getHeight() / Math.max(1, b.getWidth()))), true);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            sc.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, out);
            return android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP);
        } catch (Exception e) { return null; }
    }

    /** Remember what was playing so the home screen can offer "Fortsätt". */
    private void snapshot(android.media.session.MediaController mc) {
        try {
            android.media.MediaMetadata m = mc.getMetadata();
            if (m == null) return;
            CharSequence t = m.getText(android.media.MediaMetadata.METADATA_KEY_TITLE);
            if (t == null) t = m.getText(android.media.MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            if (t == null || t.length() == 0) return;
            CharSequence a = m.getText(android.media.MediaMetadata.METADATA_KEY_ARTIST);
            if (a == null) a = m.getText(android.media.MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
            JSONObject o = new JSONObject();
            o.put("pkg", mc.getPackageName());
            try { o.put("app", getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(mc.getPackageName(), 0)).toString()); } catch (Exception ignored) {}
            o.put("title", t.toString());
            o.put("sub", a == null ? "" : a.toString());
            o.put("t", System.currentTimeMillis());
            String art = art64(m);
            if (art != null) o.put("art", art);
            getSharedPreferences("hem", MODE_PRIVATE).edit().putString("lastMedia", o.toString()).apply();
        } catch (Exception ignored) {}
    }

    /** Pause what is playing behind Hem: tv and video always, music too when all=true. Returns the packages paused. */
    java.util.List<String> pauseMedia(boolean all) {
        java.util.List<String> done = new java.util.ArrayList<>();
        try {
            android.media.session.MediaSessionManager msm = (android.media.session.MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            for (android.media.session.MediaController mc : msm.getActiveSessions(new android.content.ComponentName(this, MediaListener.class))) {
                String pk = mc.getPackageName();
                if (pk.equals(getPackageName())) continue;
                android.media.session.PlaybackState st = mc.getPlaybackState();
                int k = st == null ? 0 : st.getState();
                boolean active = k == android.media.session.PlaybackState.STATE_PLAYING || k == android.media.session.PlaybackState.STATE_BUFFERING;
                if (active || done.isEmpty()) snapshot(mc);
                if (!active && pk.equals(HomeKeyService.TELIA) && !HomeKeyService.teliaAllowed) { try { mc.getTransportControls().stop(); } catch (Exception ignored) {} }
                if (!active || (!all && isMusic(this, pk))) continue;
                mc.getTransportControls().pause();
                done.add(pk);
            }
        } catch (Exception ignored) {}
        if (!done.isEmpty()) android.util.Log.d("HemTv", "pausade " + done);
        return done;
    }

    /** Ask the guard (runs as the shell, may force-stop) to stop these apps for real. */
    void requestStop(java.util.Collection<String> pkgs) {
        if (pkgs.isEmpty()) return;
        try { Settings.Global.putString(getContentResolver(), "hem_stop", android.text.TextUtils.join(",", pkgs) + "@" + System.currentTimeMillis()); }
        catch (Exception ignored) {}
        android.util.Log.d("HemTv", "ber vakten stoppa " + pkgs);
    }

    private long lastSilence, lastHardStop;
    /** Called every couple of seconds while Hem is on screen: tv/video sound behind it is stopped. */
    void silenceBehind() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastSilence < 1800 || now - HomeKeyService.requestAt < 8000) return;
        lastSilence = now;
        java.util.List<String> p = pauseMedia(false);
        boolean musicOn = false;
        try {
            android.media.session.MediaSessionManager msm = (android.media.session.MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            for (android.media.session.MediaController mc : msm.getActiveSessions(new android.content.ComponentName(this, MediaListener.class))) {
                android.media.session.PlaybackState st = mc.getPlaybackState();
                if (st != null && st.getState() == android.media.session.PlaybackState.STATE_PLAYING && isMusic(this, mc.getPackageName())) musicOn = true;
            }
        } catch (Exception ignored) {}
        boolean sound = ((android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE)).isMusicActive();
        if (p.isEmpty() && (!sound || musicOn)) return;
        if (p.isEmpty()) {
            if (now - lastHardStop < 20000) return;
            lastHardStop = now;
            java.util.List<String> stop = new java.util.ArrayList<>();
            stop.add(HomeKeyService.TELIA); stop.add("com.teliacompany.tv.teletext");
            for (ApplicationInfo ai : getPackageManager().getInstalledApplications(0))
                if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) == 0 && !ai.packageName.equals(getPackageName()) && !isMusic(this, ai.packageName)) stop.add(ai.packageName);
            requestStop(stop);
        } else requestStop(p);
        // sound without a controllable player: close tv and video apps (not music)
        new Thread(() -> {
            try {
                Thread.sleep(500);
                ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                for (String pk : p) am.killBackgroundProcesses(pk);
                if (!p.isEmpty()) return;
                int n = 0;
                for (ApplicationInfo ai : getPackageManager().getInstalledApplications(0)) {
                    String pk = ai.packageName;
                    boolean user = (ai.flags & ApplicationInfo.FLAG_SYSTEM) == 0;
                    boolean telia = pk.equals(HomeKeyService.TELIA) || pk.equals("com.teliacompany.tv.teletext");
                    if (pk.equals(getPackageName()) || !(user || telia) || isMusic(this, pk)) continue;
                    am.killBackgroundProcesses(pk); n++;
                }
                android.util.Log.d("HemTv", "ljud bakom Hem utan spelare: stängde " + n + " appar");
            } catch (Exception ignored) {}
        }).start();
    }

    /** Stop tv/video sound behind Hem and close those apps. */
    private void stopVideoBehind() {
        if (android.os.SystemClock.uptimeMillis() - HomeKeyService.requestAt < 5000) return;
        new Thread(() -> {
            try {
                Thread.sleep(300);
                java.util.List<String> p = pauseMedia(false);
                if (p.isEmpty()) return;
                Thread.sleep(600);
                ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                for (String pk : p) am.killBackgroundProcesses(pk);
            } catch (Exception ignored) {}
        }).start();
    }

    /** Back on Hem: close apps left running in the background (Pluto, YouTube, Telia…) so the box has its
     *  memory and processor for the home screen. Music that is playing (e.g. Spotify) is left alone. */
    private void autoTrim() {
        long now = android.os.SystemClock.uptimeMillis();
        // HOME closes the app you came from, wherever you were
        final String last = HomeKeyService.lastApp;
        HomeKeyService.lastApp = null;
        if (last != null && !last.equals(getPackageName())) {
            new Thread(() -> {
                try {
                    Thread.sleep(800);
                    if (last.equals(HomeKeyService.TELIA) && HomeKeyService.teliaAllowed) return;
                    ((ActivityManager) getSystemService(Context.ACTIVITY_SERVICE)).killBackgroundProcesses(last);
                    if (!isMusic(this, last)) requestStop(java.util.Collections.singletonList(last));
                    android.util.Log.d("HemTv", "stängde " + last);
                } catch (Exception ignored) {}
            }).start();
        }
        if (now - lastTrim < 60000 || now - HomeKeyService.requestAt < 30000) return;
        lastTrim = now;
        new Thread(() -> {
            try {
                Thread.sleep(3000);
                java.util.Set<String> playing = new java.util.HashSet<>();
                try {
                    android.media.session.MediaSessionManager msm = (android.media.session.MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
                    for (android.media.session.MediaController mc : msm.getActiveSessions(new android.content.ComponentName(this, MediaListener.class))) {
                        android.media.session.PlaybackState st = mc.getPlaybackState();
                        if (st != null && st.getState() == android.media.session.PlaybackState.STATE_PLAYING && isMusic(this, mc.getPackageName())) playing.add(mc.getPackageName());
                    }
                } catch (Exception ignored) {}
                ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                int n = 0;
                for (ApplicationInfo ai : getPackageManager().getInstalledApplications(0)) {
                    String pk = ai.packageName;
                    boolean user = (ai.flags & ApplicationInfo.FLAG_SYSTEM) == 0;
                    boolean telia = pk.equals(HomeKeyService.TELIA) || pk.equals("com.teliacompany.tv.teletext");
                    if (pk.equals(getPackageName()) || playing.contains(pk) || !(user || telia)) continue;
                    if (telia && HomeKeyService.teliaAllowed) continue;
                    am.killBackgroundProcesses(pk);
                    n++;
                }
                android.util.Log.d("HemTv", "städade bakgrunden (" + n + " appar, spelar: " + playing + ")");
            } catch (Exception ignored) {}
        }).start();
    }

    /** The guard (started with 'hemvakt' from the phone) stops when the box restarts: say so on the TV. */
    /** Our particles are the box's screensaver everywhere, not Google's. */
    private void ensureDream() {
        try {
            String me = getPackageName() + "/.ParticleDream";
            android.content.ContentResolver cr = getContentResolver();
            if (!me.equals(Settings.Secure.getString(cr, "screensaver_components"))) {
                Settings.Secure.putString(cr, "screensaver_components", me);
                Settings.Secure.putString(cr, "screensaver_default_component", me);
                Settings.Secure.putInt(cr, "screensaver_enabled", 1);
                android.util.Log.d("HemTv", "skärmsläckare satt till Hem partiklar");
            }
        } catch (Exception e) { android.util.Log.d("HemTv", "skärmsläckare: " + e); }
    }

    private void checkGuard() {
        ensureDream();
        AdbSelf.ensureGuard(this);
        try {
            String g = Settings.Global.getString(getContentResolver(), "hem_guard");
            long at = g == null ? 0 : Long.parseLong(g.trim());
            long now = System.currentTimeMillis();
            if (false && now - at > 120000 && now - guardWarned > 600000 && web != null) {
                guardWarned = now;
                web.postDelayed(() -> web.evaluateJavascript(
                    "window.toast&&toast('Vakten är avstängd (boxen har startat om). Kör hemvakt i Termux.')", null), 1500);
            }
        } catch (Exception ignored) {}
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (earth != null) earth.onResume();
        // (a channel request that is just starting must not be cancelled by this resume)
        if (android.os.SystemClock.uptimeMillis() - HomeKeyService.requestAt > 5000) {
            HomeKeyService.teliaAllowed = false;
            try {
                if (!"0".equals(Settings.Global.getString(getContentResolver(), "hem_telia")))
                    Settings.Global.putString(getContentResolver(), "hem_telia", "0");
            } catch (Exception ignored) {}
        }
        HomeKeyService.uncover();
        stopVideoBehind();
        autoTrim();
        ensureHomeKey();
        checkGuard();
        fetchRemote();
        if (web != null) web.evaluateJavascript("window.oceanShown && oceanShown()", null);
    }

    private void immersive() {
        web.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onWindowFocusChanged(boolean f) {
        super.onWindowFocusChanged(f);
        if (f) immersive();
    }

    // ---------- remote keys ----------
    private void send(String k) {
        web.evaluateJavascript("window.oceanKey && oceanKey('" + k + "')", null);
    }

    private static boolean isOk(int c) {
        return c == KeyEvent.KEYCODE_DPAD_CENTER || c == KeyEvent.KEYCODE_ENTER || c == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (isOk(code)) {
            if (e.getRepeatCount() == 0) { longFired = false; e.startTracking(); }
            return true;
        }
        String k = null;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: k = "left"; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: k = "right"; break;
            case KeyEvent.KEYCODE_DPAD_UP: k = "up"; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: k = "down"; break;
            case KeyEvent.KEYCODE_BACK: k = "back"; break;
        }
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) k = "n" + (code - KeyEvent.KEYCODE_0);
        switch (code) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: case KeyEvent.KEYCODE_MEDIA_PLAY: case KeyEvent.KEYCODE_MEDIA_PAUSE: k = "playkey"; break;
            case KeyEvent.KEYCODE_MEDIA_RECORD: case KeyEvent.KEYCODE_PROG_RED: k = "rec"; break;
            case KeyEvent.KEYCODE_LAST_CHANNEL: k = "last"; break;
            case KeyEvent.KEYCODE_ALL_APPS: k = "allapps"; break;
            case KeyEvent.KEYCODE_GUIDE: case KeyEvent.KEYCODE_TV: case KeyEvent.KEYCODE_DVR: k = "guide"; break;
            case KeyEvent.KEYCODE_CHANNEL_UP: k = "chup"; break;
            case KeyEvent.KEYCODE_CHANNEL_DOWN: k = "chdown"; break;
            case KeyEvent.KEYCODE_MENU: case KeyEvent.KEYCODE_SETTINGS: case KeyEvent.KEYCODE_INFO: k = "menu"; break;
            case KeyEvent.KEYCODE_ESCAPE: k = "back"; break;
            case KeyEvent.KEYCODE_PROG_GREEN: case KeyEvent.KEYCODE_PROG_YELLOW: case KeyEvent.KEYCODE_PROG_BLUE:
            case KeyEvent.KEYCODE_BOOKMARK: case KeyEvent.KEYCODE_CAPTIONS: k = "none"; break;
        }
        if (k != null) { if (!"none".equals(k)) send(k); return true; }
        return super.onKeyDown(code, e);
    }

    @Override
    public boolean onKeyLongPress(int code, KeyEvent e) {
        if (isOk(code)) { longFired = true; send("hold"); return true; }
        return super.onKeyLongPress(code, e);
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        if (isOk(code)) {
            if (!longFired && !e.isCanceled()) send("ok");
            longFired = false;
            return true;
        }
        return super.onKeyUp(code, e);
    }

    @Override
    public void onBackPressed() { send("back"); }

    // ---------- bluetooth ----------
    private boolean btAllowed() {
        return Build.VERSION.SDK_INT < 31
            || checkSelfPermission("android.permission.BLUETOOTH_CONNECT") == PackageManager.PERMISSION_GRANTED;
    }

    private void initBluetooth() {
        try {
            if (!btAllowed()) return;
            BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
            if (ad == null) return;
            ad.getProfileProxy(this, new BluetoothProfile.ServiceListener() {
                public void onServiceConnected(int p, BluetoothProfile proxy) { a2dp = proxy; }
                public void onServiceDisconnected(int p) { a2dp = null; }
            }, BluetoothProfile.A2DP);
        } catch (Exception ignored) {}
    }

    // ---------- JS bridge ----------
    private class Bridge {
        @JavascriptInterface
        public String getApps() {
            PackageManager pm = getPackageManager();
            Map<String, ResolveInfo> map = new LinkedHashMap<>();
            Intent lb = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            for (ResolveInfo ri : pm.queryIntentActivities(lb, 0)) map.put(ri.activityInfo.packageName, ri);
            Intent nm = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            for (ResolveInfo ri : pm.queryIntentActivities(nm, 0))
                if (!map.containsKey(ri.activityInfo.packageName)) map.put(ri.activityInfo.packageName, ri);
            List<ResolveInfo> list = new ArrayList<>(map.values());
            Collections.sort(list, new Comparator<ResolveInfo>() {
                public int compare(ResolveInfo a, ResolveInfo b) {
                    return a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString());
                }
            });
            JSONArray arr = new JSONArray();
            String self = getPackageName();
            for (ResolveInfo ri : list) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(self)) continue;
                try {
                    JSONObject o = new JSONObject();
                    o.put("label", ri.loadLabel(pm).toString());
                    o.put("pkg", pkg);
                    o.put("icon", toB64(ri.loadIcon(pm), 96, 96));
                    Drawable bn = ri.activityInfo.loadBanner(pm);
                    if (bn == null) bn = ri.activityInfo.applicationInfo.loadBanner(pm);
                    if (bn != null) o.put("banner", toB64(bn, 320, 180));
                    arr.put(o);
                } catch (Exception ignored) {}
            }
            return arr.toString();
        }

        @JavascriptInterface
        public void play(String kind, String url, String title) {
            startActivity(new Intent(HomeActivity.this, PlayerActivity.class)
                .putExtra("kind", kind).putExtra("url", url).putExtra("title", title));
        }

        @JavascriptInterface
        public void openTelia(String label) {
            HomeKeyService.request(label);
            // let the cover screen appear first, then start Telia behind it
            try { Thread.sleep(350); } catch (InterruptedException ignored) {}
            // start Telia fresh on its start page, never where it was left last time
            try {
                allowTelia();
                Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .setClassName(HomeKeyService.TELIA, HomeKeyService.TELIA + ".MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    .setIdentifier("hem-allow");
                startActivity(i);
            } catch (Exception e) {
                launch(HomeKeyService.TELIA);
            }
        }

        /** The page says whether the native Earth behind it is visible (paused under full-screen overlays). */
        @JavascriptInterface
        public void earth(final boolean on, final boolean slow) {
            ui.post(() -> {
                if (earth == null) return;
                earth.setSlow(slow);
                earth.setShown(on);
            });
        }

        @JavascriptInterface
        public void earthSaver(final boolean on) {
            ui.post(() -> { if (earth != null) earth.setSaver(on); refresh60(on); });
            if (on) stopVideoBehind();
            saverIsOn = on;
            if (on) ui.postDelayed(() -> { if (saverIsOn) Ambient.start(HomeActivity.this); }, 1500); else Ambient.stop();
        }

        @JavascriptInterface
        public int saverSec(int set) {
            if (set >= 0) getSharedPreferences("hem", MODE_PRIVATE).edit().putInt("saverSec", set).apply();
            HomeKeyService.touch();
            return HomeKeyService.saverSec(HomeActivity.this);
        }

        @JavascriptInterface
        public void showSaver() {
            ui.post(() -> { try { startActivity(new Intent(HomeActivity.this, SaverActivity.class).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)); } catch (Exception ignored) {} });
        }

        @JavascriptInterface
        public int saverTune(int set) {
            android.content.SharedPreferences p = getSharedPreferences("hem", MODE_PRIVATE);
            if (set >= 0) p.edit().putInt("saverTune", set).putBoolean("saverMusic", set != Ambient.NONE).apply();
            return p.getInt("saverTune", 0);
        }

        @JavascriptInterface
        public int saverVol(int set) {
            android.content.SharedPreferences p = getSharedPreferences("hem", MODE_PRIVATE);
            if (set >= 1 && set <= 5) p.edit().putInt("saverVol", set).apply();
            return p.getInt("saverVol", 3);
        }

        @JavascriptInterface
        public void previewMusic() { Ambient.preview(HomeActivity.this); }

        @JavascriptInterface
        public boolean saverMusic(int set) {
            android.content.SharedPreferences p = getSharedPreferences("hem", MODE_PRIVATE);
            if (set >= 0) p.edit().putBoolean("saverMusic", set == 1).apply();
            return p.getBoolean("saverMusic", true);
        }

        /** Tell the guard (Guard.java) that the next Telia start is ours. */
        private void allowTelia() {
            try { Settings.Global.putString(getContentResolver(), "hem_telia", String.valueOf(System.currentTimeMillis())); }
            catch (Exception ignored) {}
        }

        /** Music/video that is playing (or paused) right now, for the player on the home screen. */
        private String npArtKey = "";
        @JavascriptInterface
        public String nowPlaying(String haveArt) {
            silenceBehind();
            try {
                android.media.session.MediaSessionManager msm = (android.media.session.MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
                android.media.session.MediaController best = null;
                for (android.media.session.MediaController mc : msm.getActiveSessions(new android.content.ComponentName(HomeActivity.this, MediaListener.class))) {
                    if (mc.getPackageName().equals(getPackageName())) continue;
                    android.media.session.PlaybackState st = mc.getPlaybackState();
                    if (st == null || mc.getMetadata() == null) continue;
                    int k = st.getState();
                    if (k == android.media.session.PlaybackState.STATE_PLAYING) { best = mc; break; }
                    if (best == null && (k == android.media.session.PlaybackState.STATE_PAUSED || k == android.media.session.PlaybackState.STATE_BUFFERING)) best = mc;
                }
                if (best == null) return "null";
                android.media.MediaMetadata m = best.getMetadata();
                JSONObject o = new JSONObject();
                CharSequence t = m.getText(android.media.MediaMetadata.METADATA_KEY_TITLE);
                if (t == null) t = m.getText(android.media.MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
                CharSequence a = m.getText(android.media.MediaMetadata.METADATA_KEY_ARTIST);
                if (a == null) a = m.getText(android.media.MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
                o.put("pkg", best.getPackageName());
                try { o.put("app", getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(best.getPackageName(), 0)).toString()); } catch (Exception ignored) {}
                o.put("title", t == null ? "" : t.toString());
                o.put("sub", a == null ? "" : a.toString());
                o.put("playing", best.getPlaybackState().getState() == android.media.session.PlaybackState.STATE_PLAYING);
                String key = best.getPackageName() + "|" + o.optString("title");
                o.put("key", key);
                if (!key.equals(haveArt)) {
                    android.graphics.Bitmap b = m.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART);
                    if (b == null) b = m.getBitmap(android.media.MediaMetadata.METADATA_KEY_ART);
                    if (b == null) b = m.getBitmap(android.media.MediaMetadata.METADATA_KEY_DISPLAY_ICON);
                    if (b != null) {
                        android.graphics.Bitmap sc = android.graphics.Bitmap.createScaledBitmap(b, 220, Math.max(1, Math.round(220f * b.getHeight() / Math.max(1, b.getWidth()))), true);
                        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                        sc.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, out);
                        o.put("art", android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP));
                    }
                }
                return o.toString();
            } catch (Exception e) { return "null"; }
        }

        /** Play/pause, next, previous for whatever is playing. */
        @JavascriptInterface
        public void mediaCmd(String cmd) {
            try {
                android.media.session.MediaSessionManager msm = (android.media.session.MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
                for (android.media.session.MediaController mc : msm.getActiveSessions(new android.content.ComponentName(HomeActivity.this, MediaListener.class))) {
                    android.media.session.PlaybackState st = mc.getPlaybackState();
                    if (st == null || mc.getPackageName().equals(getPackageName())) continue;
                    int k = st.getState();
                    if (k != android.media.session.PlaybackState.STATE_PLAYING && k != android.media.session.PlaybackState.STATE_PAUSED) continue;
                    android.media.session.MediaController.TransportControls tc = mc.getTransportControls();
                    if ("toggle".equals(cmd)) { if (k == android.media.session.PlaybackState.STATE_PLAYING) tc.pause(); else tc.play(); }
                    else if ("next".equals(cmd)) tc.skipToNext();
                    else if ("prev".equals(cmd)) tc.skipToPrevious();
                    return;
                }
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public String getMedia() {
            return getSharedPreferences("media", MODE_PRIVATE).getString("all", "{}");
        }

        @JavascriptInterface
        public void launch(String pkg) {
            try {
                PackageManager pm = getPackageManager();
                Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
                if (i == null) i = pm.getLaunchIntentForPackage(pkg);
                if (i == null) i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(pkg);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void openUri(String uri, String pkg) {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                if (pkg != null && !pkg.isEmpty()) i.setPackage(pkg);
                startActivity(i);
            } catch (Exception e) {
                try { i.setPackage(null); startActivity(i); } catch (Exception ignored) {}
            }
        }

        @JavascriptInterface
        public void action(String what) {
            String a = null;
            switch (what) {
                case "settings": a = Settings.ACTION_SETTINGS; break;
                case "bluetooth": a = Settings.ACTION_BLUETOOTH_SETTINGS; break;
                case "wifi": a = Settings.ACTION_WIFI_SETTINGS; break;
                case "display": a = Settings.ACTION_DISPLAY_SETTINGS; break;
            }
            if (a == null) return;
            try { startActivity(new Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (Exception e) {
                try { startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                catch (Exception ignored) {}
            }
        }

        @JavascriptInterface
        public String deviceName() {
            String n = Settings.Global.getString(getContentResolver(), "device_name");
            return (n == null || n.isEmpty()) ? Build.MODEL : n;
        }

        @JavascriptInterface
        public String network() {
            try {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                Network n = cm.getActiveNetwork();
                JSONObject o = new JSONObject();
                if (n == null) { o.put("type", "none"); return o.toString(); }
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                o.put("type", nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "wifi" : "ethernet");
                LinkProperties lp = cm.getLinkProperties(n);
                if (lp != null) for (LinkAddress la : lp.getLinkAddresses())
                    if (la.getAddress() instanceof Inet4Address) o.put("ip", la.getAddress().getHostAddress());
                return o.toString();
            } catch (Exception e) { return "{\"type\":\"none\"}"; }
        }

        /** Wi-Fi details for the little Wi-Fi card on the home screen. */
        @JavascriptInterface
        public String wifiInfo() {
            try {
                JSONObject o = new JSONObject(network());
                if (!"wifi".equals(o.optString("type"))) return o.toString();
                android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                android.net.wifi.WifiInfo wi = wm.getConnectionInfo();
                if (wi != null) {
                    int rssi = wi.getRssi();
                    o.put("rssi", rssi);
                    o.put("pct", Math.max(0, Math.min(100, 2 * (rssi + 100))));
                    o.put("level", wm.calculateSignalLevel(rssi));
                    o.put("maxLevel", wm.getMaxSignalLevel());
                    o.put("speed", wi.getLinkSpeed());
                    o.put("freq", wi.getFrequency());
                    try { o.put("rx", wi.getRxLinkSpeedMbps()); o.put("tx", wi.getTxLinkSpeedMbps()); } catch (Throwable ignored) {}
                    String ss = wi.getSSID();
                    if (ss != null && !ss.contains("unknown")) o.put("ssid", ss.replace("\"", ""));
                    try { o.put("std", wi.getWifiStandard()); } catch (Throwable ignored) {}
                }
                return o.toString();
            } catch (Exception e) { return network(); }
        }

        private long[] mem() {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            return new long[]{mi.availMem / 1048576, mi.totalMem / 1048576};
        }

        /** Which box this is (set by the setup script): picks this box's own channel list. */
        @JavascriptInterface
        public String boxProfile() {
            String v = Settings.Global.getString(getContentResolver(), "hem_kanaler");
            return v == null ? "" : v.trim();
        }

        /** Address and code for the tablet remote, and the sleep timer. */
        @JavascriptInterface
        public String remoteInfo() {
            try {
                Remote r = Remote.get();
                return new JSONObject().put("url", Remote.address(HomeActivity.this)).put("code", Remote.code(HomeActivity.this))
                    .put("sleepAt", r == null ? 0 : r.sleepAt()).put("on", r != null).toString();
            } catch (Exception e) { return "null"; }
        }

        @JavascriptInterface
        public void sleepIn(int min) {
            Remote r = Remote.get();
            if (r != null) ui.post(() -> r.sleepIn(min));
        }

        /** Picture of the last video watched (taken by HomeKeyService), base64 JPEG, with its app. */
        @JavascriptInterface
        public String lastShot() {
            try {
                java.io.File f = new java.io.File(getFilesDir(), "shot.jpg");
                if (!f.exists()) return "null";
                android.content.SharedPreferences sp = getSharedPreferences("hem", MODE_PRIVATE);
                JSONObject o = new JSONObject();
                String pk = sp.getString("shotPkg", "");
                o.put("pkg", pk);
                o.put("t", sp.getLong("shotT", 0));
                try { o.put("app", getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pk, 0)).toString()); } catch (Exception ignored) {}
                o.put("img", android.util.Base64.encodeToString(java.nio.file.Files.readAllBytes(f.toPath()), android.util.Base64.NO_WRAP));
                return o.toString();
            } catch (Exception e) { return "null"; }
        }

        /** Fetch a news feed off the UI; the answer comes back as newsCb(id, text). */
        @JavascriptInterface
        public void fetchText(final String url, final String id) {
            new Thread(() -> {
                String txt = "";
                try {
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                    c.setConnectTimeout(7000); c.setReadTimeout(9000);
                    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) Hem/1.0");
                    c.setInstanceFollowRedirects(true);
                    try (java.io.InputStream in = c.getInputStream()) {
                        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                        byte[] buf = new byte[16384]; int n;
                        while ((n = in.read(buf)) > 0 && b.size() < 1500000) b.write(buf, 0, n);
                        txt = b.toString("UTF-8");
                    }
                } catch (Exception e) { android.util.Log.d("HemTv", "nyheter: " + url + " " + e); }
                final String js = "window.newsCb&&newsCb(" + JSONObject.quote(id) + "," + JSONObject.quote(txt) + ")";
                ui.post(() -> { if (web != null) web.evaluateJavascript(js, null); });
            }).start();
        }

        /** The last thing that played (title, app, cover), for "Fortsätt" on the home screen. */
        @JavascriptInterface
        public String lastMedia() {
            return getSharedPreferences("hem", MODE_PRIVATE).getString("lastMedia", "null");
        }

        @JavascriptInterface
        public void forgetMedia() {
            getSharedPreferences("hem", MODE_PRIVATE).edit().remove("lastMedia").remove("shotPkg").apply();
            new java.io.File(getFilesDir(), "shot.jpg").delete();
        }

        /** Continue: play the paused session if it still exists, otherwise open the app. */
        @JavascriptInterface
        public boolean resumeMedia(String pkg) {
            try {
                android.media.session.MediaSessionManager msm = (android.media.session.MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
                for (android.media.session.MediaController mc : msm.getActiveSessions(new android.content.ComponentName(HomeActivity.this, MediaListener.class)))
                    if (mc.getPackageName().equals(pkg) && mc.getPlaybackState() != null
                        && mc.getPlaybackState().getState() == android.media.session.PlaybackState.STATE_PAUSED) { mc.getTransportControls().play(); return true; }
            } catch (Exception ignored) {}
            return false;
        }

        @JavascriptInterface
        public String memory() {
            long[] m = mem();
            return "{\"avail\":" + m[0] + ",\"total\":" + m[1] + "}";
        }

        @JavascriptInterface
        public String freeMemory() {
            long before = mem()[0];
            try {
                pauseMedia(true);
                Thread.sleep(400);
                ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                for (ApplicationInfo ai : getPackageManager().getInstalledApplications(0))
                    if (!ai.packageName.equals(getPackageName())) am.killBackgroundProcesses(ai.packageName);
                Thread.sleep(700);
            } catch (Exception ignored) {}
            long[] after = mem();
            return "{\"before\":" + before + ",\"after\":" + after[0] + ",\"total\":" + after[1] + "}";
        }

        @JavascriptInterface
        public String bluetooth() {
            try {
                JSONObject o = new JSONObject();
                JSONArray devs = new JSONArray();
                o.put("perm", btAllowed());
                o.put("devices", devs);
                if (!btAllowed()) return o.toString();
                BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
                BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
                o.put("enabled", ad != null && ad.isEnabled());
                if (ad == null || !ad.isEnabled()) return o.toString();
                List<BluetoothDevice> con = a2dp != null ? a2dp.getConnectedDevices() : new ArrayList<>();
                for (BluetoothDevice d : ad.getBondedDevices()) {
                    JSONObject x = new JSONObject();
                    x.put("name", d.getName());
                    x.put("connected", con.contains(d));
                    devs.put(x);
                }
                return o.toString();
            } catch (Exception e) { return "{\"perm\":false,\"devices\":[]}"; }
        }
    }

    private static String toB64(Drawable d, int w, int h) {
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, w, h);
        d.draw(c);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        bmp.recycle();
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }
}
