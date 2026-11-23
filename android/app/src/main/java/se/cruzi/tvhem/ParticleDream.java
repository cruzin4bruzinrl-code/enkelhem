package se.cruzi.tvhem;

import android.service.dreams.DreamService;

/** The box's own screensaver (when you are idle inside another app): the same particles as on the home screen, instead of Google's. */
public class ParticleDream extends DreamService {
    private EarthView v;

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);
        v = new EarthView(this);
        setContentView(v);
        try {
            android.view.Display d = getWindow().getWindowManager().getDefaultDisplay();
            android.view.Display.Mode cur = d.getMode();
            for (android.view.Display.Mode m : d.getSupportedModes())
                if (m.getPhysicalWidth() == cur.getPhysicalWidth() && m.getPhysicalHeight() == cur.getPhysicalHeight() && Math.abs(m.getRefreshRate() - 60f) < 1f) {
                    android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
                    lp.preferredDisplayModeId = m.getModeId();
                    getWindow().setAttributes(lp);
                }
        } catch (Exception ignored) {}
    }

    @Override public void onDreamingStarted() {
        super.onDreamingStarted();
        if (v != null) { v.setShown(true); v.setSaver(true); }
    }

    @Override public void onDreamingStopped() {
        if (v != null) v.onPause();
        super.onDreamingStopped();
    }
}
