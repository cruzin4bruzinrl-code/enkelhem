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
    private final Runnable music = () -> Ambient.start(this);

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
        h.postDelayed(music, 1500);
    }

    @Override protected void onPause() {
        h.removeCallbacks(music);
        Ambient.stop();
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
