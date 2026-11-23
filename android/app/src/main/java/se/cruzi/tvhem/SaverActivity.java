package se.cruzi.tvhem;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/** Hem's own screensaver, started by HomeKeyService when nobody has touched the remote for a while and nothing plays.
 *  Works the same on the home screen and inside every app, and does not depend on the box's own screensaver settings. */
public class SaverActivity extends Activity {
    static volatile boolean showing;
    private EarthView v;
    private final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable music = () -> { if (Ambient.tuneOf(this) == Ambient.YT) startYt(); else Ambient.start(this); };
    private boolean ytFirst() { return Ambient.tuneOf(this) == Ambient.YT; }
    private android.webkit.WebView yt;
    static final String YT_ID = "wJNtFKCorzQ"; // "Relaxing Music with Nature Sounds – Spa Massage Music" (Beautiful Melodies), live
    private static final int[] YT_VOL = {0, 12, 22, 35, 50, 70};

    /* The stream fills the whole screen as the screensaver, played with YouTube's own embedded player.
       The particles show until the video starts; if it cannot play (no internet, embedding blocked) they stay, with the music box. */
    @android.annotation.SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void startYt() {
        if (yt != null) return;
        yt = new android.webkit.WebView(this);
        android.webkit.WebSettings ws = yt.getSettings();
        ws.setJavaScriptEnabled(true); ws.setMediaPlaybackRequiresUserGesture(false); ws.setDomStorageEnabled(true);
        yt.setBackgroundColor(0);
        yt.setFocusable(false);
        yt.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface public void fail(String why) {
                h.post(() -> { android.util.Log.d("HemTv", "youtube-musik gick inte: " + why); if (yt != null) yt.setVisibility(View.GONE); Ambient.startFallback(SaverActivity.this); });
            }
        }, "Hem");
        int vol = YT_VOL[Ambient.volOf(this)];
        String html = "<!doctype html><html><head><meta name=viewport content='width=device-width'><style>html,body{margin:0;background:transparent;overflow:hidden}"
            + "#w{position:fixed;inset:0;overflow:hidden;opacity:0;transition:opacity 2.5s}#w.on{opacity:1}#w iframe{pointer-events:none}</style></head><body><div id=w><div id=p></div></div>"
            + "<script>var t=setTimeout(function(){Hem.fail('timeout')},25000);"
            + "function onYouTubeIframeAPIReady(){new YT.Player('p',{width:'100%',height:'100%',videoId:'" + YT_ID + "',"
            + "playerVars:{autoplay:1,controls:0,disablekb:1,fs:0,iv_load_policy:3,modestbranding:1,playsinline:1,rel:0},"
            + "events:{onReady:function(e){e.target.unMute();e.target.setVolume(" + vol + ");e.target.playVideo();},"
            + "onStateChange:function(e){if(e.data===1){clearTimeout(t);document.getElementById('w').className='on';}},"
            + "onError:function(e){Hem.fail('error '+e.data)}}});}"
            + "</script><script src='https://www.youtube.com/iframe_api' onerror=\"Hem.fail('no internet')\"></script></body></html>";
        ((android.widget.FrameLayout) findViewById(android.R.id.content)).addView(yt,
            new android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        yt.loadDataWithBaseURL("https://cruzin4bruzinrl-code.github.io/", html, "text/html", "utf-8", null);
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        getWindow().getDecorView().setBackgroundColor(0xff010208);
        v = new EarthView(this);
        setContentView(v);
    }

    @Override protected void onResume() {
        super.onResume();
        showing = true;
        v.onResume(); v.setShown(true); v.setSaver(true);
        h.postDelayed(music, ytFirst() ? 100 : 1500);
    }

    @Override protected void onPause() {
        h.removeCallbacks(music);
        Ambient.stop();
        if (yt != null) { try { yt.loadUrl("about:blank"); yt.destroy(); } catch (Exception ignored) {} yt = null; }
        v.onPause();
        showing = false;
        HomeKeyService.touch();
        super.onPause();
        finish();
        overridePendingTransition(0, android.R.anim.fade_out);
    }

    private void wake() { HomeKeyService.touch(); finish(); overridePendingTransition(0, android.R.anim.fade_out); }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN || k == KeyEvent.KEYCODE_VOLUME_MUTE) return super.dispatchKeyEvent(e);
        if (e.getAction() == KeyEvent.ACTION_UP) wake();
        return true;
    }

    @Override public boolean dispatchTouchEvent(MotionEvent e) { if (e.getAction() == MotionEvent.ACTION_UP) wake(); return true; }
}
