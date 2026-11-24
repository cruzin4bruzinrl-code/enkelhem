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
        // bright picture saver (default): the home screen's own background, drifting slowly, with a big clock — it does not darken the room like the space particles
        android.content.SharedPreferences p = getSharedPreferences("hem", MODE_PRIVATE);
        if (p.getInt("saverStyle", 0) == 0 && !ytFirst()) {
            try (java.io.InputStream in = getAssets().open("bg" + p.getString("bgSuf", "") + ".jpg")) {
                pic = new android.widget.ImageView(this);
                pic.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
                pic.setImageBitmap(android.graphics.BitmapFactory.decodeStream(in));
                android.widget.FrameLayout root = findViewById(android.R.id.content);
                root.addView(pic, new android.widget.FrameLayout.LayoutParams(-1, -1));
                float d = getResources().getDisplayMetrics().density;
                clock = new android.widget.TextView(this);
                clock.setTextColor(0xffffffff); clock.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 96);
                clock.setTypeface(android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL));
                clock.setShadowLayer(18 * d, 0, 2 * d, 0x99000000); clock.setLineSpacing(0, 0.9f);
                android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(-2, -2, android.view.Gravity.BOTTOM | android.view.Gravity.START);
                lp.setMargins((int) (64 * d), 0, 0, (int) (56 * d));
                root.addView(clock, lp);
                v.setVisibility(View.GONE);
            } catch (Exception ignored) { pic = null; }
        }
    }
    private android.widget.ImageView pic;
    private android.widget.TextView clock;
    private android.animation.ValueAnimator drift;
    private final Runnable tickClock = new Runnable() { @Override public void run() {
        if (clock == null) return;
        java.util.Date now = new java.util.Date();
        String t = new java.text.SimpleDateFormat("HH:mm", new java.util.Locale("sv", "SE")).format(now);
        String d = new java.text.SimpleDateFormat("EEEE d MMMM", new java.util.Locale("sv", "SE")).format(now);
        android.text.SpannableString sp = new android.text.SpannableString(t + "\n" + d.substring(0, 1).toUpperCase() + d.substring(1));
        sp.setSpan(new android.text.style.RelativeSizeSpan(0.36f), t.length(), sp.length(), 0);
        clock.setText(sp);
        h.postDelayed(this, 15000);
    }};

    @Override protected void onResume() {
        super.onResume();
        showing = true;
        if (pic != null) {
            h.post(tickClock);
            drift = android.animation.ValueAnimator.ofFloat(0f, 1f);
            drift.setDuration(45000); drift.setRepeatMode(android.animation.ValueAnimator.REVERSE); drift.setRepeatCount(-1);
            drift.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            drift.addUpdateListener(a -> { float f = (float) a.getAnimatedValue(); pic.setScaleX(1.04f + 0.1f * f); pic.setScaleY(1.04f + 0.1f * f); pic.setTranslationX(-40 * f * getResources().getDisplayMetrics().density); });
            drift.start();
        } else { v.onResume(); v.setShown(true); v.setSaver(true); }
        h.postDelayed(music, ytFirst() ? 100 : 1500);
    }

    @Override protected void onPause() {
        h.removeCallbacks(music);
        Ambient.stop();
        if (yt != null) { try { yt.loadUrl("about:blank"); yt.destroy(); } catch (Exception ignored) {} yt = null; }
        if (drift != null) drift.cancel();
        h.removeCallbacks(tickClock);
        if (pic == null) v.onPause();
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
