package com.cardash.display;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Simple line and shape icons, drawn in code on a 24 x 24 grid. */
final class Icons extends Drawable {

    static final int PLAY = 0;
    static final int PAUSE = 1;
    static final int NEXT = 2;
    static final int PREV = 3;
    static final int MAP = 4;
    static final int APPS = 5;
    static final int SETTINGS = 6;
    static final int CLOSE = 7;
    static final int REPLY = 8;
    static final int SPEAK = 9;
    static final int MIC = 10;
    static final int NOTE = 11;

    private final int kind;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF box = new RectF();

    Icons(int kind, int color) {
        this.kind = kind;
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(color);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2.2f);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setColor(color);
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float size = Math.min(b.width(), b.height());
        if (size <= 0) return;
        c.save();
        c.translate(b.left + (b.width() - size) / 2f, b.top + (b.height() - size) / 2f);
        c.scale(size / 24f, size / 24f);
        path.reset();
        switch (kind) {
            case PLAY:
                poly(8, 5, 19, 12, 8, 19);
                c.drawPath(path, fill);
                break;
            case PAUSE:
                c.drawRect(6, 5, 10, 19, fill);
                c.drawRect(14, 5, 18, 19, fill);
                break;
            case NEXT:
                poly(6, 6, 14.5f, 12, 6, 18);
                c.drawPath(path, fill);
                c.drawRect(16, 6, 18.4f, 18, fill);
                break;
            case PREV:
                poly(18, 6, 9.5f, 12, 18, 18);
                c.drawPath(path, fill);
                c.drawRect(5.6f, 6, 8, 18, fill);
                break;
            case MAP:
                // an arrow head pointing up, like a compass needle
                poly(12, 3, 19.5f, 20, 12, 16.2f, 4.5f, 20);
                c.drawPath(path, fill);
                break;
            case APPS:
                for (int row = 0; row < 3; row++) {
                    for (int col = 0; col < 3; col++) {
                        float x = 4 + col * 6;
                        float y = 4 + row * 6;
                        box.set(x, y, x + 4, y + 4);
                        c.drawRoundRect(box, 1, 1, fill);
                    }
                }
                break;
            case SETTINGS:
                // three sliders
                c.drawLine(4, 6, 20, 6, line);
                c.drawLine(4, 12, 20, 12, line);
                c.drawLine(4, 18, 20, 18, line);
                c.drawCircle(9, 6, 2.8f, fill);
                c.drawCircle(15, 12, 2.8f, fill);
                c.drawCircle(8, 18, 2.8f, fill);
                break;
            case CLOSE:
                c.drawLine(6, 6, 18, 18, line);
                c.drawLine(18, 6, 6, 18, line);
                break;
            case REPLY:
                path.moveTo(9.5f, 6.5f);
                path.lineTo(4, 12);
                path.lineTo(9.5f, 17.5f);
                path.moveTo(4, 12);
                path.lineTo(14, 12);
                path.quadTo(20, 12, 20, 18.5f);
                c.drawPath(path, line);
                break;
            case SPEAK:
                poly(4, 9.5f, 8, 9.5f, 13, 5.5f, 13, 18.5f, 8, 14.5f, 4, 14.5f);
                c.drawPath(path, fill);
                box.set(10.5f, 8, 17.5f, 16);
                c.drawArc(box, -55, 110, false, line);
                box.set(8, 5, 21, 19);
                c.drawArc(box, -50, 100, false, line);
                break;
            case MIC:
                box.set(9.2f, 3, 14.8f, 14);
                c.drawRoundRect(box, 2.8f, 2.8f, fill);
                box.set(6.2f, 5.5f, 17.8f, 17.5f);
                c.drawArc(box, 0, 180, false, line);
                c.drawLine(12, 17.5f, 12, 21, line);
                c.drawLine(9, 21, 15, 21, line);
                break;
            case NOTE:
                // a music note: stem, flag and round head
                c.drawCircle(9, 17.5f, 3.2f, fill);
                c.drawRect(10.6f, 4.5f, 12.2f, 17.5f, fill);
                poly(12.2f, 4.5f, 18, 6.5f, 18, 10, 12.2f, 8);
                c.drawPath(path, fill);
                break;
            default:
                break;
        }
        c.restore();
    }

    private void poly(float... xy) {
        path.moveTo(xy[0], xy[1]);
        for (int i = 2; i + 1 < xy.length; i += 2) path.lineTo(xy[i], xy[i + 1]);
        path.close();
    }

    @Override
    public void setAlpha(int alpha) {
        fill.setAlpha(alpha);
        line.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        fill.setColorFilter(cf);
        line.setColorFilter(cf);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
