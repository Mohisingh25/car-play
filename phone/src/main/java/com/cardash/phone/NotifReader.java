package com.cardash.phone;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.RemoteViews;
import android.widget.TextView;

import com.cardash.link.Link;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Turns phone notifications into the small messages the display understands. */
final class NotifReader {
    private NotifReader() {}

    private static final String MAPS = "com.google.android.apps.maps";
    private static final Pattern DISTANCE = Pattern.compile("\\d+([.,]\\d+)?\\s?(m|km|ft|mi|yd)\\b");

    static String str(CharSequence cs) {
        return cs == null ? "" : cs.toString().trim();
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    // ---- Ordinary notifications -------------------------------------------------------------

    /** Returns null when the notification should not be shown on the display. */
    static JSONObject toMessage(Context app, NotificationListenerService svc, StatusBarNotification sbn,
                                boolean messagesOnly) {
        if (sbn == null || app.getPackageName().equals(sbn.getPackageName())) return null;
        Notification n = sbn.getNotification();
        if (n == null || sbn.isOngoing()) return null;
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return null;

        String cat = n.category;
        if (cat != null && (cat.equals(Notification.CATEGORY_TRANSPORT) || cat.equals(Notification.CATEGORY_SERVICE)
                || cat.equals(Notification.CATEGORY_PROGRESS) || cat.equals(Notification.CATEGORY_SYSTEM)
                || cat.equals(Notification.CATEGORY_STATUS) || cat.equals("navigation"))) {
            return null;
        }

        Bundle x = n.extras;
        if (x == null) return null;
        String title = str(x.getCharSequence(Notification.EXTRA_TITLE));
        String text = str(x.getCharSequence(Notification.EXTRA_TEXT));
        if (text.isEmpty()) text = str(x.getCharSequence(Notification.EXTRA_BIG_TEXT));
        if (title.isEmpty() && text.isEmpty()) return null;

        boolean canReply = replyAction(n) != null;
        boolean messaging = Notification.CATEGORY_MESSAGE.equals(cat) || canReply
                || x.containsKey(Notification.EXTRA_MESSAGES);
        if (messagesOnly && !messaging) return null;

        // Skip silent notifications: only things that would normally make a sound or pop up.
        try {
            NotificationListenerService.RankingMap map = svc.getCurrentRanking();
            NotificationListenerService.Ranking r = new NotificationListenerService.Ranking();
            if (map != null && map.getRanking(sbn.getKey(), r)
                    && r.getImportance() < NotificationManager.IMPORTANCE_DEFAULT) {
                return null;
            }
        } catch (Exception ignored) {
        }

        JSONObject o = Link.msg("notif");
        Link.put(o, "key", sbn.getKey());
        Link.put(o, "pkg", sbn.getPackageName());
        Link.put(o, "app", appLabel(app, sbn.getPackageName()));
        Link.put(o, "title", clip(title, 120));
        Link.put(o, "text", clip(text, 400));
        Link.put(o, "when", sbn.getPostTime());
        Link.put(o, "canReply", canReply);
        return o;
    }

    static Notification.Action replyAction(Notification n) {
        if (n == null || n.actions == null) return null;
        for (Notification.Action a : n.actions) {
            RemoteInput[] inputs = a.getRemoteInputs();
            if (inputs == null) continue;
            for (RemoteInput ri : inputs) {
                if (ri.getAllowFreeFormInput()) return a;
            }
        }
        return null;
    }

    /** Sends a typed reply through the notification's own Reply button (works for WhatsApp, SMS and similar). */
    static boolean reply(Context c, StatusBarNotification sbn, String text) {
        Notification.Action a = replyAction(sbn.getNotification());
        if (a == null || a.actionIntent == null) return false;
        RemoteInput[] inputs = a.getRemoteInputs();
        Intent fill = new Intent();
        Bundle results = new Bundle();
        for (RemoteInput ri : inputs) {
            if (ri.getAllowFreeFormInput()) results.putCharSequence(ri.getResultKey(), text);
        }
        RemoteInput.addResultsToIntent(inputs, fill, results);
        try {
            a.actionIntent.send(c, 0, fill);
            return true;
        } catch (PendingIntent.CanceledException e) {
            return false;
        }
    }

    private static String appLabel(Context app, String pkg) {
        try {
            PackageManager pm = app.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            return pkg;
        }
    }

    // ---- Google Maps turn-by-turn -----------------------------------------------------------

    /** True for the ongoing notification Google Maps shows while it is navigating. */
    static boolean isNav(StatusBarNotification sbn) {
        if (sbn == null || !MAPS.equals(sbn.getPackageName()) || !sbn.isOngoing()) return false;
        Notification n = sbn.getNotification();
        if (n == null) return false;
        if ("navigation".equals(n.category)) return true;
        Bundle x = n.extras;
        if (x == null) return false;
        String both = str(x.getCharSequence(Notification.EXTRA_TITLE)) + " "
                + str(x.getCharSequence(Notification.EXTRA_TEXT));
        return DISTANCE.matcher(both).find();
    }

    static JSONObject nav(Context app, StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        Bundle x = n.extras == null ? new Bundle() : n.extras;
        String title = str(x.getCharSequence(Notification.EXTRA_TITLE));
        String text = str(x.getCharSequence(Notification.EXTRA_TEXT));
        String sub = str(x.getCharSequence(Notification.EXTRA_SUB_TEXT));

        if (title.isEmpty() && text.isEmpty()) {
            // Some Maps versions draw their own layout; read the words out of it instead.
            List<String> parts = customTexts(app, n);
            if (parts.size() > 0) title = parts.get(0);
            if (parts.size() > 1) text = parts.get(1);
            if (parts.size() > 2) {
                StringBuilder sb = new StringBuilder();
                for (int i = 2; i < parts.size(); i++) {
                    if (sb.length() > 0) sb.append(" · ");
                    sb.append(parts.get(i));
                }
                sub = sb.toString();
            }
        }

        JSONObject o = Link.msg("nav");
        Link.put(o, "active", true);
        Link.put(o, "title", clip(title, 80));
        Link.put(o, "text", clip(text, 160));
        Link.put(o, "sub", clip(sub, 120));
        String icon = iconBase64(app, n);
        if (icon != null) Link.put(o, "icon", icon);
        return o;
    }

    static JSONObject navEnded() {
        return Link.put(Link.msg("nav"), "active", false);
    }

    private static List<String> customTexts(Context app, Notification n) {
        List<String> out = new ArrayList<>();
        try {
            RemoteViews rv = n.bigContentView != null ? n.bigContentView : n.contentView;
            if (rv == null) return out;
            View root = rv.apply(app, new FrameLayout(app));
            collect(root, out);
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void collect(View v, List<String> out) {
        if (v == null || v.getVisibility() != View.VISIBLE || out.size() >= 6) return;
        if (v instanceof TextView) {
            String s = str(((TextView) v).getText());
            if (!s.isEmpty() && !out.contains(s)) out.add(s);
        } else if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), out);
        }
    }

    /** The turn arrow Maps puts in its notification, as a small PNG. */
    private static String iconBase64(Context app, Notification n) {
        try {
            Icon large = n.getLargeIcon();
            if (large == null) return null;
            Drawable d = large.loadDrawable(app);
            if (d == null) return null;
            int size = 96;
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            d.setBounds(0, 0, size, size);
            d.draw(canvas);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.PNG, 100, bos);
            bmp.recycle();
            return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }
}
