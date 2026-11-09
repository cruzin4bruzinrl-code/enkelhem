package se.cruzi.tvhem;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * 1) Sends the remote's HOME button to this launcher (Telia's launcher stays installed for Telia Play).
 * 2) Opens a section inside Telia (Tv, Mitt innehåll, Mitt konto) by steering Telia's side menu
 *    with the D-pad, like a person would. OK is only pressed once the wanted item has focus.
 */
public class HomeKeyService extends AccessibilityService {
    static final String TELIA = "se.telia.androidtv.launcher";
    private static HomeKeyService instance;
    private static String pending;
    private static long pendingUntil;
    private static int presses;
    private final Handler h = new Handler(Looper.getMainLooper());

    static void request(String label) {
        pending = label;
        pendingUntil = SystemClock.uptimeMillis() + 10000;
        Log.d("HemTv", "öppnar " + label + ", tjänst " + (instance == null ? "SAKNAS" : "igång"));
        presses = 0;
        HomeKeyService s = instance;
        if (s != null) s.schedule(1100);
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
        Log.d("HemTv", "tjänsten startad");
        if (pending != null) schedule(900);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        if (e.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        if (e.getAction() == KeyEvent.ACTION_UP) {
            pending = null;
            try {
                startActivity(new Intent(this, HomeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            } catch (Exception ignored) {}
        }
        return true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (pending == null || e == null) return;
        CharSequence p = e.getPackageName();
        if (p != null && TELIA.contentEquals(p) && !h.hasCallbacks(tick)) schedule(500);
    }

    @Override
    public void onInterrupt() {}

    private void schedule(long ms) {
        h.removeCallbacks(tick);
        h.postDelayed(tick, ms);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (pending == null) return;
            if (SystemClock.uptimeMillis() > pendingUntil || presses > 24) { pending = null; return; }
            boolean done = false;
            try { done = step(); } catch (Exception ignored) {}
            if (done) pending = null;
            else schedule(380);
        }
    };

    /** One move per call. Returns true when finished. */
    private boolean step() {
        if (Build.VERSION.SDK_INT < 33) return true;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        CharSequence pkg = root.getPackageName();
        if (pkg == null || !TELIA.contentEquals(pkg)) return false;

        AccessibilityNodeInfo target = findLabel(root, pending);
        AccessibilityNodeInfo focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        Rect t = new Rect(), f = new Rect(), s = new Rect();
        root.getBoundsInScreen(s);
        if (target != null) target.getBoundsInScreen(t);
        if (focus != null) focus.getBoundsInScreen(f);
        Log.d("HemTv", "steg " + presses + " mål=" + (target == null ? "saknas" : t.toShortString() + (target.isVisibleToUser() ? " synlig" : " dold"))
            + " fokus=" + (focus == null ? "inget" : f.toShortString() + " " + focus.getClassName() + " \"" + focus.getText() + "\"")
            + " skärm=" + s.toShortString());
        boolean smallFocus = focus != null && f.width() < s.width() * 0.45f && f.height() < s.height() * 0.3f;
        if (target != null && target.isVisibleToUser() && smallFocus) {
            if (f.contains(t.centerX(), t.centerY())) {
                Log.d("HemTv", "OK på " + pending);
                press(GLOBAL_ACTION_DPAD_CENTER);
                return true;
            }
            boolean sameColumn = t.centerX() >= f.left - 40 && t.centerX() <= f.right + 40;
            if (sameColumn) {
                press(t.centerY() > f.centerY() ? GLOBAL_ACTION_DPAD_DOWN : GLOBAL_ACTION_DPAD_UP);
                return false;
            }
        }
        // focus is in the content or the menu is collapsed: move left towards the side menu
        press(GLOBAL_ACTION_DPAD_LEFT);
        return false;
    }

    private void press(int action) {
        Log.d("HemTv", "tryck " + action);
        presses++;
        performGlobalAction(action);
    }

    private static AccessibilityNodeInfo findLabel(AccessibilityNodeInfo root, String want) {
        List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByText(want);
        if (hits == null) return null;
        for (AccessibilityNodeInfo n : hits) {
            CharSequence t = n.getText();
            if (t != null && t.toString().trim().equalsIgnoreCase(want)) return n;
        }
        return null;
    }
}
