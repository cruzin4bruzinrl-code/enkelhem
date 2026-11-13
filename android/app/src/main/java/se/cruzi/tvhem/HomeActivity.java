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
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("hem", MODE_PRIVATE);
        if (!prefs.contains("url")) prefs.edit().putString("url", DEFAULT_URL).apply();
        cache = new File(getFilesDir(), "remote.html");
        saveUrl(getIntent());

        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF010208));
        earth = new EarthView(this);
        web = new WebView(this);
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
    private void ensureHomeKey() {
        try {
            if (checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != PackageManager.PERMISSION_GRANTED) return;
            String me = getPackageName() + "/" + HomeKeyService.class.getName();
            String cur = Settings.Secure.getString(getContentResolver(), "enabled_accessibility_services");
            if (cur != null && cur.contains(HomeKeyService.class.getName())) return;
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

    @Override
    protected void onPause() {
        super.onPause();
        if (earth != null) earth.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (earth != null) earth.onResume();
        // (a channel request that is just starting must not be cancelled by this resume)
        if (android.os.SystemClock.uptimeMillis() - HomeKeyService.requestAt > 5000) HomeKeyService.teliaAllowed = false;
        ensureHomeKey();
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
            ui.post(() -> { if (earth != null) earth.setSaver(on); });
        }

        /** Tell the guard (Guard.java) that the next Telia start is ours. */
        private void allowTelia() {
            try { Settings.Global.putString(getContentResolver(), "hem_telia_until", String.valueOf(System.currentTimeMillis() + 15000)); }
            catch (Exception ignored) {}
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

        private long[] mem() {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            return new long[]{mi.availMem / 1048576, mi.totalMem / 1048576};
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
