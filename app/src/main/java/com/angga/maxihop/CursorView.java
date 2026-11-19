package com.angga.maxihop;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/** A simple on-screen pointer drawn over the WebView, moved with the remote's D-pad. */
public class CursorView extends View {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float x = -1, y = -1;
    private boolean visible = true;

    public CursorView(Context context) {
        super(context);
        fill.setColor(Color.argb(200, 255, 255, 255));
        ring.setColor(Color.argb(230, 255, 122, 0));
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(5f);
        setFocusable(false);
        setClickable(false);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (x < 0 || y < 0) {
            x = w / 2f;
            y = h / 2f;
        } else {
            x = Math.min(x, w - 1);
            y = Math.min(y, h - 1);
        }
    }

    /** Moves the pointer. Returns true if it was pushed against a screen edge. */
    public boolean moveBy(int dx, int dy) {
        float nx = x + dx, ny = y + dy;
        float maxX = getWidth() - 1, maxY = getHeight() - 1;
        boolean edge = nx < 0 || ny < 0 || nx > maxX || ny > maxY;
        x = Math.max(0, Math.min(nx, maxX));
        y = Math.max(0, Math.min(ny, maxY));
        invalidate();
        return edge;
    }

    public void setVisible(boolean v) {
        if (visible != v) {
            visible = v;
            invalidate();
        }
    }

    public boolean isPointerVisible() { return visible; }
    public float getPointerX() { return x; }
    public float getPointerY() { return y; }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!visible || x < 0) return;
        canvas.drawCircle(x, y, 14f, fill);
        canvas.drawCircle(x, y, 16f, ring);
    }
}
