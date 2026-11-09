package se.cruzi.tvhem;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * 1) Sends the remote's HOME button to this launcher (Telia's launcher stays installed for Telia Play).
 * 2) Opens a section inside Telia (Tv, Mitt innehåll, Mitt konto) by pressing that menu item for the user.
 */
public class HomeKeyService extends AccessibilityService {
    static final String TELIA = "se.telia.androidtv.launcher";
    private static HomeKeyService instance;
    private static String pending;
    private static long pendingUntil;
    private static int leftPresses;
    private final Handler h = new Handler(Looper.getMainLooper());

    static void request(String label) {
        pending = label;
        pendingUntil = SystemClock.uptimeMillis() + 9000;
        leftPresses = 0;
        HomeKeyService s = instance;
        if (s != null) s.schedule(700);
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
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
        if (p != null && TELIA.contentEquals(p)) schedule(250);
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
            if (SystemClock.uptimeMillis() > pendingUntil) { pending = null; return; }
            boolean done = false;
            try { done = tryClick(); } catch (Exception ignored) {}
            if (done) pending = null;
            else schedule(400);
        }
    };

    private boolean tryClick() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        CharSequence pkg = root.getPackageName();
        if (pkg == null || !TELIA.contentEquals(pkg)) return false;
        String want = pending;
        List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByText(want);
        if (hits != null) {
            for (AccessibilityNodeInfo n : hits) {
                CharSequence t = n.getText();
                if (t == null || !t.toString().trim().equalsIgnoreCase(want)) continue;
                AccessibilityNodeInfo node = n;
                for (int i = 0; i < 6 && node != null; i++) {
                    if (node.isClickable() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
                    if (node.isFocusable() && Build.VERSION.SDK_INT >= 33) {
                        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                        h.postDelayed(new Runnable() {
                            @Override public void run() { performGlobalAction(GLOBAL_ACTION_DPAD_CENTER); }
                        }, 180);
                        return true;
                    }
                    node = node.getParent();
                }
            }
        }
        // menu labels are hidden while Telia's side menu is collapsed: open it with LEFT
        if (leftPresses < 3 && Build.VERSION.SDK_INT >= 33) {
            leftPresses++;
            performGlobalAction(GLOBAL_ACTION_DPAD_LEFT);
        }
        return false;
    }
}
