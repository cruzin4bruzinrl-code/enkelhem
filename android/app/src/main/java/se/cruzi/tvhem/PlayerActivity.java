package se.cruzi.tvhem;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Plays a live channel inside the launcher, full screen.
 * kind=yt  : official YouTube live stream through YouTube's embeddable player
 * kind=svt : SVT channel id (e.g. ch-svt1) played from SVT's own HLS stream
 * Logs "spelar <title>" or "fel <title> ..." under tag HemPlay, so channels can be tested from adb.
 */
@SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
public class PlayerActivity extends Activity {
    private static final String TAG = "HemPlay";
    private WebView web;
    private TextView label, msg;
    private String title = "";
    private boolean reported;
    private final Handler h = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        label = text(26, Gravity.TOP | Gravity.START);
        msg = text(30, Gravity.CENTER);
        root.addView(label, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));
        root.addView(msg, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setBackgroundColor(Color.BLACK);
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface public void playing() { report(true, ""); }
            @JavascriptInterface public void error(String e) { report(false, e); }
        }, "Hem");
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest r) {
                r.grant(new String[]{PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID});
            }
        });
        web.setWebViewClient(new WebViewClient());
        web.setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        open();
    }

    private TextView text(int size, int g) {
        TextView t = new TextView(this);
        t.setTextColor(Color.WHITE);
        t.setTextSize(size);
        t.setShadowLayer(12, 0, 2, Color.BLACK);
        t.setPadding(60, 46, 60, 46);
        t.setGravity(g);
        return t;
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        open();
    }

    private void open() {
        String kind = getIntent().getStringExtra("kind");
        final String url = getIntent().getStringExtra("url");
        title = getIntent().getStringExtra("title");
        if (title == null) title = "";
        reported = false;
        h.removeCallbacksAndMessages(null);
        msg.setText("");
        label.setText("● LIVE  " + title);
        label.setAlpha(1f);
        label.animate().alpha(0f).setStartDelay(4000).setDuration(800).start();
        Log.d(TAG, "start " + title);
        h.postDelayed(new Runnable() {
            @Override public void run() { report(false, "ingen bild efter 20 s"); }
        }, 20000);

        if ("yt".equals(kind)) {
            String page = "<html><body style='margin:0;background:#000'>"
                + "<iframe id='p' src='" + url + "&enablejsapi=1&origin=https://hem.cruzi.se' style='border:0;width:100vw;height:100vh' allow='autoplay; encrypted-media'></iframe>"
                + "<script src='https://www.youtube.com/iframe_api'></script><script>"
                + "function onYouTubeIframeAPIReady(){new YT.Player('p',{events:{"
                + "onStateChange:function(e){if(e.data==1)Hem.playing();},"
                + "onError:function(e){Hem.error('youtube fel '+e.data);}}});}"
                + "</script></body></html>";
            web.loadDataWithBaseURL("https://hem.cruzi.se/", page, "text/html", "UTF-8", null);
        } else if ("svt".equals(kind)) {
            new Thread(new Runnable() {
                @Override public void run() {
                    final String hls = svtStream(url);
                    h.post(new Runnable() {
                        @Override public void run() {
                            if (hls == null) { report(false, "svt gav ingen ström"); return; }
                            String page = "<html><body style='margin:0;background:#000'>"
                                + "<video id='v' autoplay playsinline style='width:100vw;height:100vh;background:#000' src='" + hls + "'></video><script>"
                                + "var v=document.getElementById('v');"
                                + "v.addEventListener('playing',function(){Hem.playing();});"
                                + "v.addEventListener('error',function(){Hem.error('video fel '+(v.error?v.error.code:''));});"
                                + "v.play().catch(function(e){Hem.error('play '+e);});</script></body></html>";
                            web.loadDataWithBaseURL("https://www.svtplay.se/", page, "text/html", "UTF-8", null);
                        }
                    });
                }
            }).start();
        } else {
            report(false, "okänd kanaltyp");
        }
    }

    /** Asks SVT for the channel's stream and returns an unencrypted HLS address, or null. */
    private static String svtStream(String id) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("https://api.svt.se/video/" + id).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(8000);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            JSONArray refs = new JSONObject(bo.toString("UTF-8")).optJSONArray("videoReferences");
            if (refs == null) return null;
            String best = null;
            for (int i = 0; i < refs.length(); i++) {
                JSONObject r = refs.getJSONObject(i);
                String f = r.optString("format"), u = r.optString("url");
                if (u.isEmpty() || f.contains("drm") || f.contains("dash")) continue;
                if (f.equals("hls")) return u;
                if (f.startsWith("hls") && best == null) best = u;
            }
            return best;
        } catch (Exception e) {
            Log.d(TAG, "svt api: " + e);
            return null;
        }
    }

    private void report(final boolean ok, final String why) {
        h.post(new Runnable() {
            @Override public void run() {
                if (reported) return;
                reported = true;
                h.removeCallbacksAndMessages(null);
                if (ok) Log.d(TAG, "spelar " + title);
                else {
                    Log.d(TAG, "fel " + title + ": " + why);
                    msg.setText(title + " kunde inte spelas just nu");
                }
            }
        });
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK) { finish(); return true; }
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_DPAD_UP) {
            label.animate().cancel();
            label.setAlpha(1f);
            label.animate().alpha(0f).setStartDelay(3000).setDuration(800).start();
            return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        web.loadUrl("about:blank");
        web.destroy();
        super.onDestroy();
    }
}
