package se.cruzi.tvhem;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

/** Plays a live channel inside the launcher, full screen, without leaving the app. */
@SuppressLint("SetJavaScriptEnabled")
public class PlayerActivity extends Activity {
    private WebView web;
    private TextView label;
    private final Handler h = new Handler(Looper.getMainLooper());

    /** Keeps only the video on screen, full size, playing with sound. */
    private static final String FOCUS_VIDEO =
        "(function(){var v=document.querySelector('video');if(!v)return 0;" +
        "if(!document.getElementById('hemcss')){var s=document.createElement('style');s.id='hemcss';" +
        "s.textContent='html,body{background:#000!important;overflow:hidden!important}body *{visibility:hidden!important}" +
        "video{visibility:visible!important;position:fixed!important;left:0!important;top:0!important;width:100vw!important;" +
        "height:100vh!important;object-fit:contain!important;z-index:2147483647!important;background:#000!important}';" +
        "document.head.appendChild(s);}v.muted=false;v.volume=1;if(v.paused){var p=v.play();if(p&&p.catch)p.catch(function(){});}return 1;})()";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        label = new TextView(this);
        label.setTextColor(Color.WHITE);
        label.setTextSize(26);
        label.setShadowLayer(12, 0, 2, Color.BLACK);
        label.setPadding(60, 46, 60, 46);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        root.addView(label, lp);
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUserAgentString("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36");
        web.setBackgroundColor(Color.BLACK);
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest r) {
                r.grant(new String[]{PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID});
            }
        });
        web.setWebViewClient(new WebViewClient());
        web.setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        open();
    }

    @Override
    protected void onNewIntent(android.content.Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        open();
    }

    private void open() {
        String kind = getIntent().getStringExtra("kind");
        String url = getIntent().getStringExtra("url");
        String title = getIntent().getStringExtra("title");
        label.setText(title == null ? "" : "● LIVE  " + title);
        label.setAlpha(1f);
        label.animate().alpha(0f).setStartDelay(4000).setDuration(800).start();
        h.removeCallbacksAndMessages(null);
        if ("yt".equals(kind)) {
            String page = "<html><body style=\"margin:0;background:#000\"><iframe src=\"" + url +
                "\" style=\"border:0;width:100vw;height:100vh\" allow=\"autoplay; encrypted-media\"></iframe></body></html>";
            web.loadDataWithBaseURL("https://hem.cruzi.se/", page, "text/html", "UTF-8", null);
        } else {
            web.loadUrl(url);
            for (int i = 1; i <= 20; i++) {
                h.postDelayed(new Runnable() {
                    @Override public void run() { web.evaluateJavascript(FOCUS_VIDEO, null); }
                }, i * 1000L);
            }
        }
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
