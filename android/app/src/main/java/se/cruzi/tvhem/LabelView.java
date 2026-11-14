package se.cruzi.tvhem;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;

/** Planet names in the screensaver, drawn on top of the solar system and following the planets. */
public class LabelView extends View {
    private final EarthView earth;
    private final Paint name = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG);

    public LabelView(Context c, EarthView earth) {
        super(c);
        this.earth = earth;
        name.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        name.setTextAlign(Paint.Align.CENTER);
        name.setShadowLayer(6, 0, 2, Color.BLACK);
        dot.setStyle(Paint.Style.STROKE);
        setWillNotDraw(false);
    }

    @Override
    protected void onDraw(Canvas cv) {
        float e = earth.shownE;
        if (e > 0.01f) postInvalidateDelayed(40);
        else { postInvalidateDelayed(500); return; }
        float A = Math.max(0f, Math.min(1f, (e - 0.75f) / 0.25f));
        if (A <= 0) return;
        float k = getWidth() / 1920f;
        cv.save();
        cv.scale(k, k);
        double t = earth.shownT;
        name.setTextSize(22);
        name.setLetterSpacing(0.08f);
        for (int i = 0; i < EarthView.PLANETS.length; i++) {
            float[] p = EarthView.PLANETS[i];
            double ang = t * 2 * Math.PI / p[1] + p[2];
            float x = 960f + (float) Math.cos(ang) * p[0];
            float y = 560f + (float) Math.sin(ang) * p[0] * 0.34f;
            float r = p[3] * (1f + 0.12f * (float) Math.sin(ang));
            boolean hidden = Math.sin(ang) < 0 && Math.hypot(x - 960, y - 560) < 54 + r;   // behind the Sun
            if (hidden) continue;
            float off = r + (i == 5 ? 26 : 12) + 20;
            name.setColor(Color.argb((int) (200 * A), 235, 240, 255));
            cv.drawText(EarthView.NAMES[i], x, y + off, name);
        }
        name.setTextSize(26);
        name.setColor(Color.argb((int) (220 * A), 255, 220, 170));
        cv.drawText("Solen", 960, 560 + 54 + 40, name);
        cv.restore();
    }
}
