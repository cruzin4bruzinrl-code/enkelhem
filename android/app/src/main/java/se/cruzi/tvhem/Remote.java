package se.cruzi.tvhem;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The tablet remote: a small web page served from the box (port 8090, home network only) and a WebSocket
 * for commands. The tablet becomes a touchpad with a pointer over everything on the tv, a keyboard,
 * a remote, and a voice/text assistant (Gemini) that can do whatever Hem can do.
 */
final class Remote {
    static final String TAG = "HemTv";
    static final int PORT = 8090;
    private static Remote inst;
    private AccessibilityService svc;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final SharedPreferences sp;
    private final CopyOnWriteArrayList<OutputStream> clients = new CopyOnWriteArrayList<>();
    private ServerSocket server;
    private volatile boolean stop;
    // pointer
    private View ptr;
    private WindowManager.LayoutParams plp;
    private float px = 960, py = 540, sw = 1920, sh = 1080;
    private final Runnable hidePtr = () -> { if (ptr != null) ptr.setVisibility(View.GONE); };
    // keys through the guard
    private Socket keySock;
    private PrintWriter keyOut;
    // assistant conversation
    private final List<JSONObject> chat = new ArrayList<>();
    private Runnable sleepTask;
    private long sleepAt;

    static Remote get() { return inst; }

    /** The accessibility service was restarted: keep the running server, use the new service. */
    void attach(AccessibilityService s) { svc = s; h.post(() -> { ptr = null; }); }

    /** Type a channel number with the digit keys (through the guard). */
    void digits(String num) {
        for (char ch : num.toCharArray()) if (ch >= '0' && ch <= '9') {
            guardKey(android.view.KeyEvent.KEYCODE_0 + (ch - '0'));
            // a pause between the digits: two equal keys in a row ("11", "222") must not look like one
            keyQ.execute(() -> { try { Thread.sleep(220); } catch (InterruptedException ignored) {} });
        }
    }

    Remote(AccessibilityService s) {
        svc = s;
        sp = s.getSharedPreferences("hem", Context.MODE_PRIVATE);
        inst = this;
    }

    static String code(Context c) {
        SharedPreferences p = c.getSharedPreferences("hem", Context.MODE_PRIVATE);
        String k = p.getString("remoteCode", null);
        if (k == null) { k = String.format(Locale.ROOT, "%04d", new Random().nextInt(10000)); p.edit().putString("remoteCode", k).apply(); }
        return k;
    }

    static String address(Context c) {
        try {
            for (java.net.NetworkInterface ni : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces()))
                for (java.net.InetAddress a : java.util.Collections.list(ni.getInetAddresses()))
                    if (!a.isLoopbackAddress() && a instanceof java.net.Inet4Address) return "http://" + a.getHostAddress() + ":" + PORT;
        } catch (Exception ignored) {}
        return "";
    }

    void start() {
        try {
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            ((WindowManager) svc.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
            sw = dm.widthPixels; sh = dm.heightPixels; px = sw / 2; py = sh / 2;
        } catch (Exception ignored) {}
        code(svc);
        new Thread(() -> {
            while (!stop) {
                try (ServerSocket ss = new ServerSocket(PORT)) {
                    server = ss;
                    Log.d(TAG, "plattan: server på " + address(svc));
                    while (!stop) { Socket so = ss.accept(); new Thread(() -> serve(so)).start(); }
                } catch (Exception e) { Log.d(TAG, "plattan: server fel " + e); }
                try { Thread.sleep(4000); } catch (InterruptedException ignored) {}
            }
        }).start();
    }

    void stop() {
        stop = true;
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        h.post(() -> { try { if (ptr != null) ((WindowManager) svc.getSystemService(Context.WINDOW_SERVICE)).removeView(ptr); } catch (Exception ignored) {} ptr = null; });
    }

    // ---------------- http + websocket ----------------

    private void serve(Socket so) {
        try {
            so.setSoTimeout(0);
            InputStream in = so.getInputStream();
            OutputStream out = so.getOutputStream();
            String req = readLine(in);
            if (req == null) { so.close(); return; }
            String key = null, line;
            while ((line = readLine(in)) != null && !line.isEmpty())
                if (line.toLowerCase(Locale.ROOT).startsWith("sec-websocket-key:")) key = line.substring(18).trim();
            String path = req.split(" ").length > 1 ? req.split(" ")[1] : "/";
            if (path.startsWith("/ws")) {
                if (key == null || !path.contains("k=" + code(svc))) { http(out, 403, "text/plain", "fel kod".getBytes(StandardCharsets.UTF_8)); so.close(); return; }
                String acc = Base64.encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)), Base64.NO_WRAP);
                out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + acc + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.flush();
                so.setTcpNoDelay(true);
                clients.add(out);
                sendTo(out, hello());
                try { wsLoop(in, out); } finally { clients.remove(out); so.close(); }
                return;
            }
            if (path.equals("/") || path.startsWith("/?")) {
                http(out, 200, "text/html; charset=utf-8", asset("remote.html"));
            } else if (path.startsWith("/icon")) {
                String pkg = Uri.parse("http://x" + path).getQueryParameter("pkg");
                byte[] png = icon(pkg);
                if (png == null) http(out, 404, "text/plain", new byte[0]); else http(out, 200, "image/png", png);
            } else if (path.startsWith("/check")) {
                boolean ok = path.contains("k=" + code(svc));
                http(out, ok ? 200 : 403, "text/plain", (ok ? "ok" : "fel").getBytes(StandardCharsets.UTF_8));
            } else http(out, 404, "text/plain", "saknas".getBytes(StandardCharsets.UTF_8));
            so.close();
        } catch (Exception e) {
            try { so.close(); } catch (Exception ignored) {}
        }
    }

    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) { if (c == '\n') break; if (c != '\r') b.write(c); if (b.size() > 8192) break; }
        if (c == -1 && b.size() == 0) return null;
        return b.toString("UTF-8");
    }

    private static void http(OutputStream o, int code, String type, byte[] body) throws Exception {
        o.write(("HTTP/1.1 " + code + " OK\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
            + "\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        o.write(body);
        o.flush();
    }

    private byte[] asset(String n) throws Exception {
        try (InputStream in = svc.getAssets().open(n)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384]; int k;
            while ((k = in.read(buf)) > 0) b.write(buf, 0, k);
            return b.toByteArray();
        }
    }

    private void wsLoop(InputStream in, OutputStream out) throws Exception {
        while (true) {
            int b0 = in.read(), b1 = in.read();
            if (b0 < 0 || b1 < 0) return;
            int op = b0 & 0x0f;
            long len = b1 & 0x7f;
            if (len == 126) len = (in.read() << 8) | in.read();
            else if (len == 127) { len = 0; for (int i = 0; i < 8; i++) len = (len << 8) | in.read(); }
            byte[] mask = new byte[4];
            if ((b1 & 0x80) != 0) for (int i = 0; i < 4; i++) mask[i] = (byte) in.read();
            if (len > 1_000_000) return;
            byte[] data = new byte[(int) len];
            int got = 0;
            while (got < len) { int r = in.read(data, got, (int) len - got); if (r < 0) return; got += r; }
            if ((b1 & 0x80) != 0) for (int i = 0; i < data.length; i++) data[i] ^= mask[i % 4];
            if (op == 8) return;
            if (op == 9) { frame(out, 10, data); continue; }
            if (op != 1) continue;
            try { handle(new JSONObject(new String(data, StandardCharsets.UTF_8)), out); }
            catch (Exception e) { Log.d(TAG, "plattan: kommando fel " + e); }
        }
    }

    private static synchronized void frame(OutputStream o, int op, byte[] d) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0x80 | op);
        if (d.length < 126) b.write(d.length);
        else if (d.length < 65536) { b.write(126); b.write(d.length >> 8); b.write(d.length & 0xff); }
        else { b.write(127); for (int i = 7; i >= 0; i--) b.write((int) (((long) d.length >> (8 * i)) & 0xff)); }
        b.write(d);
        o.write(b.toByteArray());
        o.flush();
    }

    private void sendTo(OutputStream o, JSONObject j) { try { frame(o, 1, j.toString().getBytes(StandardCharsets.UTF_8)); } catch (Exception ignored) {} }

    void broadcast(JSONObject j) { for (OutputStream o : clients) sendTo(o, j); }

    private JSONObject hello() {
        JSONObject o = new JSONObject();
        try {
            o.put("t", "hello");
            o.put("apps", apps());
            o.put("gemini", !sp.getString("geminiKey", "").isEmpty());
            o.put("sleepAt", sleepAt);
            o.put("np", nowPlaying());
        } catch (Exception ignored) {}
        return o;
    }

    // ---------------- commands ----------------

    private void handle(JSONObject c, OutputStream from) throws Exception {
        String t = c.optString("t");
        switch (t) {
            case "move": h.post(() -> move((float) c.optDouble("dx"), (float) c.optDouble("dy"))); break;
            case "tap": h.post(() -> tap(c.optBoolean("long"))); break;
            case "scroll": h.post(() -> scroll((float) c.optDouble("dx"), (float) c.optDouble("dy"))); break;
            case "key": key(c.optString("k")); break;
            case "text": h.post(() -> text(c.optString("s"), c.optBoolean("enter"))); break;
            case "launch": h.post(() -> launch(c.optString("pkg"))); break;
            case "url": h.post(() -> openUrl(c.optString("u"))); break;
            case "channel": h.post(() -> channel(c.optString("name"))); break;
            case "media": media(c.optString("c")); break;
            case "vol": volume(c.optString("c")); break;
            case "sleep": h.post(() -> sleepIn(c.optInt("min"))); break;
            case "youtube": h.post(() -> youtube(c.optString("q"))); break;
            case "state": sendTo(from, hello()); break;
            case "setkey": sp.edit().putString("geminiKey", c.optString("key").trim()).apply(); sendTo(from, hello()); break;
            case "ask": {
                final JSONArray chans = c.optJSONArray("channels");
                new Thread(() -> ask(c.optString("q"), chans, from)).start();
                break;
            }
            default: break;
        }
    }

    // pointer over everything, drawn by the accessibility service
    private void ensurePtr() {
        if (ptr != null) return;
        ptr = new View(svc) {
            final Paint f = new Paint(Paint.ANTI_ALIAS_FLAG), s = new Paint(Paint.ANTI_ALIAS_FLAG);
            final Path p = new Path();
            { f.setColor(Color.WHITE); s.setColor(0xff16181d); s.setStyle(Paint.Style.STROKE); s.setStrokeWidth(4); s.setStrokeJoin(Paint.Join.ROUND);
              p.moveTo(4, 4); p.lineTo(4, 50); p.lineTo(16, 39); p.lineTo(25, 58); p.lineTo(33, 54); p.lineTo(24, 36); p.lineTo(40, 36); p.close(); }
            @Override protected void onDraw(Canvas c) { c.drawPath(p, f); c.drawPath(p, s); }
        };
        plp = new WindowManager.LayoutParams(48, 64, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT);
        plp.gravity = Gravity.TOP | Gravity.START;
        plp.x = (int) px; plp.y = (int) py;
        try { ((WindowManager) svc.getSystemService(Context.WINDOW_SERVICE)).addView(ptr, plp); } catch (Exception e) { ptr = null; }
    }

    private void showPtr() {
        ensurePtr();
        if (ptr == null) return;
        ptr.setVisibility(View.VISIBLE);
        plp.x = (int) px; plp.y = (int) py;
        try { ((WindowManager) svc.getSystemService(Context.WINDOW_SERVICE)).updateViewLayout(ptr, plp); } catch (Exception ignored) {}
        h.removeCallbacks(hidePtr);
        h.postDelayed(hidePtr, 6000);
    }

    private void move(float dx, float dy) {
        px = Math.max(0, Math.min(sw - 2, px + dx));
        py = Math.max(0, Math.min(sh - 2, py + dy));
        showPtr();
    }

    private void tap(boolean lng) {
        showPtr();
        Path p = new Path();
        p.moveTo(px + 2, py + 2);
        GestureDescription g = new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p, 0, lng ? 800 : 60)).build();
        svc.dispatchGesture(g, null, null);
    }

    private void scroll(float dx, float dy) {
        showPtr();
        float x0 = px, y0 = py, x1 = Math.max(1, Math.min(sw - 1, x0 - dx * 2)), y1 = Math.max(1, Math.min(sh - 1, y0 - dy * 2));
        if (Math.abs(x1 - x0) < 2 && Math.abs(y1 - y0) < 2) return;
        Path p = new Path();
        p.moveTo(x0, y0);
        p.lineTo(x1, y1);
        svc.dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p, 0, 180)).build(), null, null);
    }

    void key(String k) {
        switch (k) {
            case "home": h.post(this::home); return;
            case "back":
                if (homeFront()) { HomeActivity.jsKey("back"); return; }
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK); return;
            case "vup": volume("up"); return;
            case "vdown": volume("down"); return;
            case "mute": volume("mute"); return;
            case "play": media("toggle"); return;
            default: break;
        }
        int code;
        switch (k) {
            case "up": code = 19; break;
            case "down": code = 20; break;
            case "left": code = 21; break;
            case "right": code = 22; break;
            case "ok": code = 23; break;
            case "menu": code = 82; break;
            case "del": code = 67; break;
            case "enter": code = 66; break;
            case "power": code = 223; break;
            default: return;
        }
        if (homeFront()) {
            String js = code == 19 ? "up" : code == 20 ? "down" : code == 21 ? "left" : code == 22 ? "right" : code == 23 ? "ok" : code == 82 ? "menu" : null;
            if (js != null) { HomeActivity.jsKey(js); return; }
        }
        guardKey(code);
    }

    private boolean homeFront() {
        String c = HomeKeyService.fgCls;
        return c != null && c.contains("HomeActivity");
    }

    private final java.util.concurrent.ExecutorService keyQ = java.util.concurrent.Executors.newSingleThreadExecutor();

    /** Press a key anywhere, through the guard (it runs as the shell). Never on the main thread. */
    private void guardKey(int code) { keyQ.execute(() -> { if (!guardKeyNow(code)) Log.d(TAG, "plattan: vakten tar inte emot tangenter"); }); }

    private boolean guardKeyNow(int code) {
        for (int i = 0; i < 2; i++) {
            try {
                if (keyOut == null) {
                    keySock = new Socket();
                    keySock.connect(new java.net.InetSocketAddress("127.0.0.1", 8091), 800);
                    keyOut = new PrintWriter(keySock.getOutputStream(), true);
                }
                keyOut.println("K " + code);
                if (keyOut.checkError()) throw new Exception("stängd");
                return true;
            } catch (Exception e) {
                try { if (keySock != null) keySock.close(); } catch (Exception ignored) {}
                keySock = null; keyOut = null;
            }
        }
        return false;
    }

    private void home() {
        Intent i = new Intent(svc, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        svc.startActivity(i);
    }

    private void text(String s, boolean enter) {
        AccessibilityNodeInfo root = svc.getRootInActiveWindow();
        AccessibilityNodeInfo f = root == null ? null : root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (f != null && f.isEditable()) {
            Bundle b = new Bundle();
            b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, s);
            f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
            if (enter && !f.performAction(android.R.id.accessibilityActionImeEnter)) guardKey(66);
        } else if (homeFront()) {
            HomeActivity.runJs("window.remoteText&&remoteText(" + JSONObject.quote(s) + ")");
        } else {
            // nothing to type into: search YouTube for it
            youtube(s);
        }
    }

    private void launch(String pkg) {
        PackageManager pm = svc.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) return;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { svc.startActivity(i); } catch (Exception ignored) {}
    }

    private void openUrl(String u) {
        if (u == null || u.isEmpty()) return;
        if (!u.matches("(?i)^[a-z][a-z0-9+.-]*:.*")) u = "https://" + u;
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { svc.startActivity(i); } catch (Exception ignored) {}
    }

    private void youtube(String q) {
        for (String pkg : new String[]{"com.google.android.youtube.tv", "com.teamsmart.videomanager.tv"}) {
            try {
                Intent i = new Intent(Intent.ACTION_SEARCH).setPackage(pkg).putExtra(SearchManager.QUERY, q).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                svc.startActivity(i);
                return;
            } catch (Exception ignored) {}
        }
        openUrl("https://www.youtube.com/results?search_query=" + Uri.encode(q));
    }

    private void channel(String name) {
        home();
        h.postDelayed(() -> HomeActivity.runJs("window.remoteChannel&&remoteChannel(" + JSONObject.quote(name) + ")"), 700);
    }

    private android.media.session.MediaController controller() {
        try {
            android.media.session.MediaSessionManager msm = (android.media.session.MediaSessionManager) svc.getSystemService(Context.MEDIA_SESSION_SERVICE);
            List<android.media.session.MediaController> l = msm.getActiveSessions(new android.content.ComponentName(svc, MediaListener.class));
            for (android.media.session.MediaController mc : l)
                if (mc.getPlaybackState() != null && mc.getPlaybackState().getState() == android.media.session.PlaybackState.STATE_PLAYING) return mc;
            return l.isEmpty() ? null : l.get(0);
        } catch (Exception e) { return null; }
    }

    private JSONObject nowPlaying() {
        JSONObject o = new JSONObject();
        try {
            android.media.session.MediaController mc = controller();
            if (mc == null || mc.getMetadata() == null) return o;
            android.media.MediaMetadata m = mc.getMetadata();
            o.put("title", String.valueOf(m.getText(android.media.MediaMetadata.METADATA_KEY_TITLE)));
            CharSequence a = m.getText(android.media.MediaMetadata.METADATA_KEY_ARTIST);
            o.put("sub", a == null ? "" : a.toString());
            o.put("pkg", mc.getPackageName());
            o.put("playing", mc.getPlaybackState() != null && mc.getPlaybackState().getState() == android.media.session.PlaybackState.STATE_PLAYING);
        } catch (Exception ignored) {}
        return o;
    }

    private void media(String c) {
        android.media.session.MediaController mc = controller();
        if (mc == null) { if (c.equals("toggle")) guardKey(85); return; }
        android.media.session.MediaController.TransportControls tc = mc.getTransportControls();
        boolean playing = mc.getPlaybackState() != null && mc.getPlaybackState().getState() == android.media.session.PlaybackState.STATE_PLAYING;
        switch (c) {
            case "next": tc.skipToNext(); break;
            case "prev": tc.skipToPrevious(); break;
            case "pause": tc.pause(); break;
            case "play": tc.play(); break;
            default: if (playing) tc.pause(); else tc.play();
        }
        h.postDelayed(() -> { try { JSONObject j = new JSONObject().put("t", "np").put("np", nowPlaying()); broadcast(j); } catch (Exception ignored) {} }, 600);
    }

    private void volume(String c) {
        AudioManager am = (AudioManager) svc.getSystemService(Context.AUDIO_SERVICE);
        int dir = c.equals("up") ? AudioManager.ADJUST_RAISE : c.equals("down") ? AudioManager.ADJUST_LOWER : AudioManager.ADJUST_TOGGLE_MUTE;
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI);
    }

    /** Sleep timer: after min minutes pause everything, go home and put the box (and tv over HDMI) to sleep. 0 cancels. */
    void sleepIn(int min) {
        if (sleepTask != null) h.removeCallbacks(sleepTask);
        sleepTask = null; sleepAt = 0;
        if (min > 0) {
            sleepAt = System.currentTimeMillis() + min * 60000L;
            sleepTask = () -> {
                sleepAt = 0;
                media("pause");
                home();
                h.postDelayed(() -> guardKey(223), 1500);
                Log.d(TAG, "insomningstimer: somnar");
            };
            h.postDelayed(sleepTask, min * 60000L);
        }
        sp.edit().putLong("sleepAt", sleepAt).apply();
        try { broadcast(new JSONObject().put("t", "sleep").put("sleepAt", sleepAt)); } catch (Exception ignored) {}
        Log.d(TAG, "insomningstimer: " + (min > 0 ? min + " min" : "av"));
    }

    long sleepAt() { return sleepAt; }

    // ---------------- apps + icons ----------------

    private JSONArray apps() {
        JSONArray a = new JSONArray();
        PackageManager pm = svc.getPackageManager();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                String pk = ri.activityInfo.packageName;
                if (!seen.add(pk) || pk.equals(svc.getPackageName()) || pk.equals(HomeKeyService.TELIA)) continue;
                try { a.put(new JSONObject().put("pkg", pk).put("label", String.valueOf(ri.loadLabel(pm)))); } catch (Exception ignored) {}
            }
        }
        return a;
    }

    private byte[] icon(String pkg) {
        try {
            PackageManager pm = svc.getPackageManager();
            Drawable d = pm.getApplicationIcon(pkg);
            Bitmap b = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            d.setBounds(0, 0, 128, 128);
            d.draw(c);
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 100, o);
            return o.toByteArray();
        } catch (Exception e) { return null; }
    }

    // ---------------- assistant (Gemini) ----------------

    private void ask(String q, JSONArray chans, OutputStream from) {
        String key = sp.getString("geminiKey", "");
        try {
            if (key.isEmpty()) { sendTo(from, new JSONObject().put("t", "say").put("s", "Lägg in en Gemini-nyckel under Inställningar på plattan först.")); return; }
            StringBuilder appList = new StringBuilder();
            JSONArray ap = apps();
            for (int i = 0; i < ap.length(); i++) appList.append(ap.getJSONObject(i).getString("label")).append(" = ").append(ap.getJSONObject(i).getString("pkg")).append("\n");
            String sys = "Du är rösten för Robs tv (Hem, en Android TV-box från Telia). Svara på svenska, kort och vänligt. "
                + "Du kan prata om vad som helst, och du kan styra tv:n med actions. Svara ALLTID som JSON: {\"say\":\"det du säger\",\"actions\":[...]}. "
                + "Möjliga actions: {\"do\":\"channel\",\"name\":\"<kanalnamn>\"}, {\"do\":\"launch\",\"pkg\":\"<paket>\"}, {\"do\":\"youtube\",\"q\":\"<sökning>\"}, "
                + "{\"do\":\"url\",\"u\":\"<webbadress>\"}, {\"do\":\"media\",\"c\":\"toggle|pause|play|next|prev\"}, {\"do\":\"vol\",\"c\":\"up|down|mute\",\"n\":<antal steg>}, "
                + "{\"do\":\"key\",\"k\":\"up|down|left|right|ok|back|home|menu\"}, {\"do\":\"text\",\"s\":\"<text att skriva i fältet>\"}, "
                + "{\"do\":\"sleep\",\"min\":<minuter, 0 stänger av timern>}, {\"do\":\"home\"}, {\"do\":\"clean\"}, {\"do\":\"show\",\"what\":\"weather|news|wifi\"}. "
                + "Använd bara paket och kanaler från listorna. Om något inte går, säg det ärligt och gör inga actions.\n"
                + "Kanaler: " + (chans == null ? "" : chans.toString()) + "\nAppar:\n" + appList
                + "Spelas nu: " + nowPlaying() + "\nKlockan: " + new java.text.SimpleDateFormat("EEEE d MMMM HH:mm", new Locale("sv")).format(new java.util.Date());
            chat.add(new JSONObject().put("role", "user").put("parts", new JSONArray().put(new JSONObject().put("text", q))));
            while (chat.size() > 12) chat.remove(0);
            JSONObject body = new JSONObject()
                .put("system_instruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", sys))))
                .put("contents", new JSONArray(chat))
                .put("generationConfig", new JSONObject().put("responseMimeType", "application/json").put("temperature", 0.6));
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent").openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(8000); c.setReadTimeout(30000);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("x-goog-api-key", key);
            try (OutputStream o = c.getOutputStream()) { o.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            int rc = c.getResponseCode();
            InputStream in = rc < 400 ? c.getInputStream() : c.getErrorStream();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) { String l; while ((l = r.readLine()) != null) sb.append(l); }
            if (rc >= 400) { sendTo(from, new JSONObject().put("t", "say").put("s", "Gemini svarade med fel " + rc + ". Kolla nyckeln.")); Log.d(TAG, "gemini " + rc + " " + sb); chat.remove(chat.size() - 1); return; }
            String txt = new JSONObject(sb.toString()).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text");
            chat.add(new JSONObject().put("role", "model").put("parts", new JSONArray().put(new JSONObject().put("text", txt))));
            JSONObject ans = new JSONObject(txt.substring(txt.indexOf('{'), txt.lastIndexOf('}') + 1));
            sendTo(from, new JSONObject().put("t", "say").put("s", ans.optString("say")));
            JSONArray acts = ans.optJSONArray("actions");
            if (acts != null) for (int i = 0; i < acts.length(); i++) {
                final JSONObject a = acts.getJSONObject(i);
                final int delay = i * 900;
                h.postDelayed(() -> doAction(a), delay);
            }
        } catch (Exception e) {
            Log.d(TAG, "gemini fel " + e);
            try { sendTo(from, new JSONObject().put("t", "say").put("s", "Jag fick inget svar just nu.")); } catch (Exception ignored) {}
        }
    }

    private void doAction(JSONObject a) {
        try {
            switch (a.optString("do")) {
                case "channel": channel(a.optString("name")); break;
                case "launch": launch(a.optString("pkg")); break;
                case "youtube": youtube(a.optString("q")); break;
                case "url": openUrl(a.optString("u")); break;
                case "media": media(a.optString("c")); break;
                case "vol": { int n = Math.max(1, Math.min(15, a.optInt("n", 1))); for (int i = 0; i < n; i++) volume(a.optString("c")); break; }
                case "key": key(a.optString("k")); break;
                case "text": text(a.optString("s"), true); break;
                case "sleep": sleepIn(a.optInt("min")); break;
                case "home": home(); break;
                case "clean": home(); h.postDelayed(() -> HomeActivity.runJs("window.cleanMem&&cleanMem()"), 800); break;
                case "show": {
                    String w = a.optString("what");
                    home();
                    h.postDelayed(() -> HomeActivity.runJs("window.remoteShow&&remoteShow(" + JSONObject.quote(w) + ")"), 800);
                    break;
                }
                default: break;
            }
        } catch (Exception e) { Log.d(TAG, "action fel " + e); }
    }
}
