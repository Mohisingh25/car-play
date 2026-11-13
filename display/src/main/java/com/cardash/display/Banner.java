package com.cardash.display;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;

import com.cardash.link.Ui;

/** A small pop-up at the top of the screen for a new message while another app (such as Maps) is open. */
final class Banner {

    private final Context c;
    private final WindowManager wm;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable autoHide = this::hide;
    private View view;

    Banner(Context c) {
        this.c = c;
        this.wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
    }

    void show(DashState.Notif n) {
        hide();
        if (wm == null || !Settings.canDrawOverlays(c)) return;

        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        float u = Math.min(dm.widthPixels, dm.heightPixels) / 100f;
        int pad = Math.round(2.4f * u);

        LinearLayout box = Ui.col(c);
        box.setPadding(pad * 2, pad, pad * 2, pad);
        box.setBackground(Ui.roundStroke(Ui.CARD_HI, 2.5f * u, Ui.ACCENT, Math.max(2, Math.round(0.3f * u))));
        String head = n.app.isEmpty() ? n.title : n.app + " · " + n.title;
        box.addView(Ui.oneLine(Ui.text(c, head, 3.3f * u, Ui.TEXT, true)));
        if (!n.text.isEmpty()) {
            View body = Ui.oneLine(Ui.text(c, n.text, 3.0f * u, Ui.MUTED, false));
            body.setPadding(0, Math.round(0.8f * u), 0, 0);
            box.addView(body);
        }
        box.setOnClickListener(v -> {
            hide();
            try {
                c.startActivity(new Intent(c, DashboardActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {
            }
        });

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                Math.round(dm.widthPixels * 0.62f),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = Math.round(3f * u);
        try {
            wm.addView(box, lp);
            view = box;
            h.postDelayed(autoHide, 7000);
        } catch (Exception ignored) {
            view = null;
        }
    }

    void hide() {
        h.removeCallbacks(autoHide);
        if (view != null) {
            try {
                wm.removeView(view);
            } catch (Exception ignored) {
            }
            view = null;
        }
    }
}
