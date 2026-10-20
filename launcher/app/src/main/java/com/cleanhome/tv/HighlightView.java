package com.cleanhome.tv;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.OvershootInterpolator;

/**
 * A single translucent "glass" selection panel that springs from the previous
 * focused tile's rectangle to the next. Drawn as an overlay over the whole
 * screen in absolute (root) coordinates. No blur, no per-tile borders.
 */
public class HighlightView extends View {

    private final RectF current = new RectF();
    private final RectF from = new RectF();
    private final RectF to = new RectF();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float corner;
    private boolean hasRect = false;
    private ValueAnimator animator;

    public HighlightView(Context c, AttributeSet a) {
        super(c, a);
        float d = getResources().getDisplayMetrics().density;
        corner = 18 * d;
        fill.setColor(0x26FFFFFF);
        fill.setStyle(Paint.Style.FILL);
        stroke.setColor(0x66FFFFFF);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.5f * d);
        setWillNotDraw(false);
    }

    public void setCornerRadiusDp(float dp) {
        corner = dp * getResources().getDisplayMetrics().density;
        invalidate();
    }

    /** Move the highlight to a new rect, with an optional spring animation. */
    public void moveTo(RectF target, boolean animate) {
        if (animator != null) animator.cancel();
        if (!hasRect || !animate) {
            current.set(target);
            hasRect = true;
            invalidate();
            return;
        }
        from.set(current);
        to.set(target);
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(240);
        animator.setInterpolator(new OvershootInterpolator(1.6f));
        animator.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            current.left = from.left + (to.left - from.left) * t;
            current.top = from.top + (to.top - from.top) * t;
            current.right = from.right + (to.right - from.right) * t;
            current.bottom = from.bottom + (to.bottom - from.bottom) * t;
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!hasRect || getAlpha() == 0f) return;
        canvas.drawRoundRect(current, corner, corner, fill);
        canvas.drawRoundRect(current, corner, corner, stroke);
    }
}
