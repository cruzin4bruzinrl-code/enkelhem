package se.cruzi.tvhem;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayDeque;

/**
 * 1) Sends the remote's HOME button to this launcher (Telia's launcher stays installed for Telia Play).
 * 2) Opens a section inside Telia (Tv, Mitt innehåll, Mitt konto) by steering Telia's side menu with
 *    the D-pad behind a cover screen, so Telia's start page is never seen. For "Tv" it also starts the
 *    first channel in the guide.
 */
public class HomeKeyService extends AccessibilityService {
    static final String TELIA = "se.telia.androidtv.launcher";
    private static final String TAG = "HemTv";
    private static HomeKeyService instance;
    private static String pending;
    /** true while the user is in Telia because we sent them there (a channel, Mitt innehåll …). */
    static volatile boolean teliaAllowed;
    private long lastBounce;
    private static String channel;
    private static int scans;
    private static long pendingUntil;
    private static int presses;
    private static int phase;
    private static long phaseUntil;
    private static long phaseStart;
    private static int settle;
    private static int retries;
    private static int stuck;
    private static int backs;
    private static int liveClicks;
    private static boolean liveDone;
    private static int rights;
    static volatile long requestAt;
    private static String lastFocus = "";
    private final Handler h = new Handler(Looper.getMainLooper());
    private View overlay;

    static boolean running() { return instance != null; }

    static void request(String label) {
        channel = null;
        int c = label.indexOf(':');
        if (c > 0) { channel = label.substring(c + 1).trim(); label = label.substring(0, c); }
        scans = 0;
        teliaAllowed = true;
        pending = label;
        pendingUntil = SystemClock.uptimeMillis() + 20000;
        presses = 0;
        phase = 0;
        settle = 0;
        retries = 0;
        stuck = 0;
        backs = 0;
        liveClicks = 0;
        liveDone = false;
        rights = 0;
        requestAt = SystemClock.uptimeMillis();
        lastFocus = "";
        HomeKeyService s = instance;
        Log.d(TAG, "öppnar " + label + ", tjänst " + (s == null ? "SAKNAS" : "igång"));
        if (s != null) {
            s.showOverlay(label);
            s.schedule(1100);
        }
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
        Log.d(TAG, "tjänsten startad");
        if (pending != null) { showOverlay(pending); schedule(900); }
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        hideOverlay();
        return super.onUnbind(intent);
    }

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        // Back while watching a Telia channel: straight home to Hem instead of into Telia's guide/start page
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK && pending == null && teliaAllowed && inTelia()) {
            if (e.getAction() == KeyEvent.ACTION_UP) {
                Log.d(TAG, "Bakåt i Telia, tillbaka till Hem");
                goHem();
            }
            return true;
        }
        if (e.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        if (e.getAction() == KeyEvent.ACTION_UP) {
            Log.d(TAG, "HOME-knappen" + (inTelia() ? " i Telia" : ""));
            finish("HOME");
            goHem();
        }
        return true;
    }

    /** Back to Hem, and tell the guard right away that Telia is no longer allowed. */
    private void goHem() {
        teliaAllowed = false;
        try { android.provider.Settings.Global.putString(getContentResolver(), "hem_telia", "0"); } catch (Exception ignored) {}
        try {
            startActivity(new Intent(this, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        } catch (Exception ignored) {}
    }

    /** Telia really is on screen without us asking: bounce to Hem, and look again shortly in case it comes back. */
    private int watchTries;
    private String lastCls = "";
    private final Runnable watch = new Runnable() {
        @Override public void run() {
            if (pending != null || teliaAllowed) { watchTries = 0; return; }
            if (!inTelia()) { watchTries = 0; return; }
            Log.d(TAG, "Telias launcher dök upp av sig själv (" + lastCls + "), tillbaka till Hem");
            goHem();
            if (++watchTries < 6) h.postDelayed(this, 400); else watchTries = 0;
        }
    };

    private boolean inTelia() {
        try {
            AccessibilityNodeInfo r = getRootInActiveWindow();
            CharSequence p = r == null ? null : r.getPackageName();
            return p != null && TELIA.contentEquals(p);
        } catch (Exception ex) { return false; }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null) return;
        CharSequence p = e.getPackageName();
        // Telia's own HOME handling (or anything else) opened Telia's launcher without us asking: go back to Hem.
        // Telia's start page showing when we are not steering a fresh channel request (e.g. Telia's player
        // went back to it by itself after HOME): bounce too, even while a Telia channel is allowed.
        if (pending == null && p != null && TELIA.contentEquals(p)
                && e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence cls = e.getClassName();
            boolean startPage = cls != null && cls.toString().endsWith(".MainActivity");
            if (startPage && SystemClock.uptimeMillis() - finishedAt > 2000) {
                Log.d(TAG, "Telias startsida visades efter kanalen, tillbaka till Hem");
                teliaAllowed = false;
            }
        }
        if (pending == null && !teliaAllowed && p != null && TELIA.contentEquals(p)
                && e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            lastCls = String.valueOf(e.getClassName());
            h.removeCallbacks(watch);
            h.post(watch);
            return;
        }
        if (pending == null) return;
        if (p != null && TELIA.contentEquals(p) && !h.hasCallbacks(tick)) schedule(500);
    }

    @Override
    public void onInterrupt() {}

    private void schedule(long ms) {
        h.removeCallbacks(tick);
        h.postDelayed(tick, ms);
    }

    private long finishedAt;

    private void finish(String why) {
        if (pending != null) {
            Log.d(TAG, "klar: " + why);
            finishedAt = SystemClock.uptimeMillis();
            // the channel is chosen: Telia may keep playing, but its start page is no longer allowed
            if (teliaAllowed) try { android.provider.Settings.Global.putString(getContentResolver(), "hem_telia", "2"); } catch (Exception ignored) {}
        }
        pending = null;
        h.removeCallbacks(tick);
        hideOverlay();
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (pending == null) return;
            if (SystemClock.uptimeMillis() > pendingUntil || presses > 30) { finish("gav upp"); return; }
            boolean done = false;
            try { done = step(); } catch (Exception ex) { Log.d(TAG, "fel: " + ex); }
            if (done) finish("lyckades");
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
        Rect s = new Rect(), f = new Rect(), t = new Rect();
        root.getBoundsInScreen(s);
        AccessibilityNodeInfo focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focus != null) focus.getBoundsInScreen(f);

        if (phase == 2) {
            // After a channel is chosen Telia asks what to do ("Titta live" or "Titta på kanalen", sometimes below
            // "Starta"/"Se från början"): move the focus to the live choice and press OK.
            AccessibilityNodeInfo live = findLabel(root, "Titta live");
            if (live == null) live = findLabel(root, "Titta på kanalen");
            if (live != null && live.isVisibleToUser() && !liveDone && liveClicks < 8) {
                liveClicks++;
                live.getBoundsInScreen(t);
                if (focus != null && f.contains(t.centerX(), t.centerY())) {
                    Log.d(TAG, "väljer " + live.getText());
                    press(GLOBAL_ACTION_DPAD_CENTER);
                    liveDone = true;
                    phaseStart = 0;
                    phaseUntil = SystemClock.uptimeMillis() + 6000;
                } else if (focus != null && t.centerY() > f.centerY()) {
                    press(GLOBAL_ACTION_DPAD_DOWN);
                } else if (focus != null && t.centerY() < f.centerY()) {
                    press(GLOBAL_ACTION_DPAD_UP);
                } else {
                    click(live);
                    liveDone = true;
                }
                return false;
            }
            if (live != null) return SystemClock.uptimeMillis() > phaseUntil;
            // a channel was chosen: Telia's question box comes a moment later, wait for it before letting go
            if (channel != null && !liveDone && SystemClock.uptimeMillis() < phaseUntil) return false;
            boolean guide = findLabel(root, "Favoritkanaler") != null || findLabel(root, "Tv-guide") != null;
            if (!guide && phaseStart == 0) phaseStart = SystemClock.uptimeMillis();
            if (guide) phaseStart = 0;
            if (phaseStart != 0 && SystemClock.uptimeMillis() - phaseStart > 700) return true;
            return SystemClock.uptimeMillis() > phaseUntil;
        }

        if (phase == 1) {
            // Tv-guide: wait until it has loaded, move focus into the channel grid, then start the channel
            if (findLabel(root, "Favoritkanaler") == null && findLabel(root, "Tv-guide") == null) {
                Log.d(TAG, "väntar på tv-guiden");
                if (SystemClock.uptimeMillis() - phaseStart > 1600 && retries < 2) {
                    AccessibilityNodeInfo tv = findLabel(root, "Tv");
                    if (tv != null && focus != null) {
                        tv.getBoundsInScreen(t);
                        if (f.contains(t.centerX(), t.centerY())) {
                            retries++;
                            Log.d(TAG, "försöker igen " + retries);
                            click(focus);
                            phaseStart = SystemClock.uptimeMillis();
                        } else {
                            phase = 0; settle = 0;   // focus moved away: steer back to the Tv row
                        }
                    }
                }
                return false;
            }
            Log.d(TAG, "guide fokus=" + (focus == null ? "inget" : f.toShortString()));
            if (focus == null) return false;
            if (f.left < s.width() * 0.2f && rights < 2) { rights++; press(GLOBAL_ACTION_DPAD_RIGHT); return false; }
            if (f.centerY() < s.height() * 0.47f) { press(GLOBAL_ACTION_DPAD_DOWN); return false; }
            if (channel != null) {
                AccessibilityNodeInfo ch = findContains(root, channel, s.width());
                if (ch != null && ch.isVisibleToUser()) {
                    Rect c = new Rect();
                    ch.getBoundsInScreen(c);
                    if (c.centerY() < f.top) { press(GLOBAL_ACTION_DPAD_UP); return false; }
                    if (c.centerY() > f.bottom) { press(GLOBAL_ACTION_DPAD_DOWN); return false; }
                    Log.d(TAG, "rätt kanal: " + channel);
                } else if (ROWS.containsKey(channel.toLowerCase())) {
                    // the channel logos have no text: use the channel's row in Telia's guide
                    if (scans++ == 0) dumpLeft(root, s.width());
                    int want = ROWS.get(channel.toLowerCase());
                    int cur = Math.round((f.top - 540) / 76f) + 1;
                    if (cur < want && scans < 20) { Log.d(TAG, "rad " + cur + " -> " + want); press(GLOBAL_ACTION_DPAD_DOWN); return false; }
                    if (cur > want && scans < 20) { Log.d(TAG, "rad " + cur + " -> " + want); press(GLOBAL_ACTION_DPAD_UP); return false; }
                    Log.d(TAG, "rätt rad för " + channel + ": " + cur);
                } else if (scans++ < 14) {
                    Log.d(TAG, "letar efter " + channel);
                    press(GLOBAL_ACTION_DPAD_DOWN);
                    return false;
                } else {
                    Log.d(TAG, "hittade inte " + channel + ", tar markerad kanal");
                }
            }
            press(GLOBAL_ACTION_DPAD_CENTER);
            phase = 2;
            phaseStart = 0;
            phaseUntil = SystemClock.uptimeMillis() + 10000;
            return false;
        }

        AccessibilityNodeInfo target = findLabel(root, pending);
        if (target != null) target.getBoundsInScreen(t);
        Log.d(TAG, "steg " + presses + " mål=" + (target == null ? "saknas" : t.toShortString() + (target.isVisibleToUser() ? " synlig" : " dold"))
            + " fokus=" + (focus == null ? "inget" : f.toShortString()));
        boolean smallFocus = focus != null && f.width() < s.width() * 0.45f && f.height() < s.height() * 0.3f;
        if (target != null && target.isVisibleToUser() && smallFocus) {
            if (f.contains(t.centerX(), t.centerY())) {
                if (settle++ < 1) { Log.d(TAG, "väntar in markeringen"); return false; }
                Log.d(TAG, "OK på " + pending);
                click(focus);
                if ("Tv".equalsIgnoreCase(pending)) { phase = 1; phaseStart = SystemClock.uptimeMillis(); return false; }
                phase = 2;
                phaseUntil = SystemClock.uptimeMillis() + 900;
                return false;
            }
            settle = 0;
            boolean sameColumn = t.centerX() >= f.left - 40 && t.centerX() <= f.right + 40;
            if (sameColumn) {
                press(t.centerY() > f.centerY() ? GLOBAL_ACTION_DPAD_DOWN : GLOBAL_ACTION_DPAD_UP);
                return false;
            }
        }
        String fk = f.toShortString();
        if (fk.equals(lastFocus)) stuck++; else stuck = 0;
        lastFocus = fk;
        if (stuck >= 2 && backs < 4) {
            backs++; stuck = 0;
            Log.d(TAG, "fast på en undersida, trycker Bakåt");
            press(GLOBAL_ACTION_BACK);
            return false;
        }
        press(GLOBAL_ACTION_DPAD_LEFT);
        return false;
    }

    /** Click the focused row directly; fall back to the OK key. */
    private void click(AccessibilityNodeInfo node) {
        boolean ok = node != null && node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        Log.d(TAG, "klick " + (ok ? "direkt" : "via OK-knappen"));
        if (ok) presses++;
        else press(GLOBAL_ACTION_DPAD_CENTER);
    }

    private void press(int action) {
        Log.d(TAG, "tryck " + action);
        presses++;
        performGlobalAction(action);
    }

    /** Walks the whole tree ourselves: Telia's menu is not found by findAccessibilityNodeInfosByText. */
    private static AccessibilityNodeInfo findLabel(AccessibilityNodeInfo root, String want) {
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        int seen = 0;
        while (!q.isEmpty() && seen < 3000) {
            AccessibilityNodeInfo n = q.poll();
            if (n == null) continue;
            seen++;
            if (matches(n.getText(), want) || matches(n.getContentDescription(), want)) return n;
            for (int i = 0; i < n.getChildCount(); i++) q.add(n.getChild(i));
        }
        return null;
    }

    /** First small node whose text or description contains the word (channel logos carry a description). */
    /** Row of each channel in Telia's guide (1 = top row): svt1, svt2, TV4, Kunskapskanalen, Axess, SVT Barn. */
    private static final java.util.Map<String, Integer> ROWS = new java.util.HashMap<>();
    static { ROWS.put("tv4", 3); ROWS.put("axess", 5); }

    /** Log what Telia shows in the channel column, so the guide's layout can be checked from adb. */
    private static void dumpLeft(AccessibilityNodeInfo root, int screenW) {
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        Rect r = new Rect();
        int seen = 0;
        while (!q.isEmpty() && seen < 1500) {
            AccessibilityNodeInfo n = q.poll();
            if (n == null) continue;
            seen++;
            n.getBoundsInScreen(r);
            if (r.left < 300 && r.top > 500 && r.width() < 400)
                Log.d(TAG, "kolumn " + r.toShortString() + " text=" + n.getText() + " beskr=" + n.getContentDescription() + " id=" + n.getViewIdResourceName());
            for (int i = 0; i < n.getChildCount(); i++) q.add(n.getChild(i));
        }
    }

    private static AccessibilityNodeInfo findContains(AccessibilityNodeInfo root, String want, int screenW) {
        String w = want.toLowerCase();
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        int seen = 0;
        Rect r = new Rect();
        while (!q.isEmpty() && seen < 3000) {
            AccessibilityNodeInfo n = q.poll();
            if (n == null) continue;
            seen++;
            CharSequence t = n.getText(), d = n.getContentDescription();
            boolean hit = (t != null && t.toString().toLowerCase().contains(w)) || (d != null && d.toString().toLowerCase().contains(w));
            if (hit) { n.getBoundsInScreen(r); if (r.width() < screenW / 2) return n; }
            for (int i = 0; i < n.getChildCount(); i++) q.add(n.getChild(i));
        }
        return null;
    }

    private static boolean matches(CharSequence c, String want) {
        return c != null && c.toString().trim().equalsIgnoreCase(want);
    }

    // ---------- cover screen while Telia is being steered ----------
    private void showOverlay(final String label) {
        h.post(new Runnable() {
            @Override
            public void run() {
                if (overlay != null) return;
                try {
                    LinearLayout box = new LinearLayout(HomeKeyService.this);
                    box.setOrientation(LinearLayout.VERTICAL);
                    box.setGravity(Gravity.CENTER);
                    box.setBackgroundColor(0xFF010208);
                    ProgressBar pb = new ProgressBar(HomeKeyService.this);
                    TextView tv = new TextView(HomeKeyService.this);
                    tv.setText("Tv".equalsIgnoreCase(label) ? "Startar tv…" : "Öppnar " + label + "…");
                    tv.setTextColor(0xFFFFFFFF);
                    tv.setTextSize(26);
                    tv.setPadding(0, 36, 0, 0);
                    tv.setGravity(Gravity.CENTER);
                    tv.setAlpha(0.8f);
                    box.addView(pb);
                    box.addView(tv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
                    WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.OPAQUE);
                    ((WindowManager) getSystemService(WINDOW_SERVICE)).addView(box, lp);
                    overlay = box;
                } catch (Exception ex) {
                    Log.d(TAG, "täckskärm: " + ex);
                }
            }
        });
    }

    private void hideOverlay() {
        h.post(new Runnable() {
            @Override
            public void run() {
                final View v = overlay;
                overlay = null;
                if (v == null) return;
                v.animate().alpha(0f).setDuration(350).withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        try { ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(v); } catch (Exception ignored) {}
                    }
                }).start();
            }
        });
    }
}
