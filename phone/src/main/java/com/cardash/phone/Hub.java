package com.cardash.phone;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.util.Base64;

import com.cardash.link.Link;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** The one place that ties everything on the phone together. Everything here runs on the main thread. */
final class Hub implements LinkServer.Events, MediaWatcher.Events {

    private static Hub instance;

    static synchronized Hub get(Context c) {
        if (instance == null) instance = new Hub(c.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinkServer server;
    private final MediaWatcher media;
    private final CopyOnWriteArrayList<Runnable> watchers = new CopyOnWriteArrayList<>();
    private final Map<String, Integer> sent = new HashMap<>();

    private PhoneListenerService listener;
    private PowerManager.WakeLock wake;
    private boolean started;
    private boolean batteryWatching;
    private String lastArtId = "";
    private String navKey;
    private long navSentAt;
    private StatusBarNotification navPending;
    private int lastBattery = -1;
    private boolean lastCharging;

    private Hub(Context app) {
        this.app = app;
        this.prefs = app.getSharedPreferences("cardash", Context.MODE_PRIVATE);
        this.server = new LinkServer(app, main, this);
        this.media = new MediaWatcher(app, main, this);
    }

    void start() {
        if (started) return;
        started = true;
        server.start(wifiEnabled(), code());
    }

    // ---- Settings ---------------------------------------------------------------------------

    String code() {
        String c = prefs.getString("code", "");
        if (c == null || c.length() != 6) c = newCode();
        return c;
    }

    String newCode() {
        String c = String.format("%06d", new SecureRandom().nextInt(1000000));
        prefs.edit().putString("code", c).apply();
        if (started) server.setWifi(wifiEnabled(), c);
        return c;
    }

    boolean wifiEnabled() {
        return prefs.getBoolean("wifi", false);
    }

    void setWifiEnabled(boolean on) {
        prefs.edit().putBoolean("wifi", on).apply();
        server.setWifi(on, code());
    }

    boolean messagesOnly() {
        return prefs.getBoolean("messages_only", false);
    }

    void setMessagesOnly(boolean on) {
        prefs.edit().putBoolean("messages_only", on).apply();
        if (server.isConnected()) fullSync();
    }

    // ---- Status for the setup screen ----------------------------------------------------------

    void watch(Runnable r) {
        watchers.add(r);
    }

    void unwatch(Runnable r) {
        watchers.remove(r);
    }

    private void changed() {
        for (Runnable r : watchers) r.run();
    }

    boolean connected() {
        return server.isConnected();
    }

    String transport() {
        return server.transport();
    }

    String bluetoothStatus() {
        return server.bluetoothStatus();
    }

    boolean listenerBound() {
        return listener != null;
    }

    // ---- Notification listener --------------------------------------------------------------

    void listenerUp(PhoneListenerService svc) {
        listener = svc;
        start();
        media.start();
        if (server.isConnected()) fullSync();
        changed();
    }

    void listenerDown(PhoneListenerService svc) {
        if (listener == svc) listener = null;
        changed();
    }

    void posted(StatusBarNotification sbn) {
        if (!server.isConnected() || listener == null || sbn == null) return;
        if (NotifReader.isNav(sbn)) {
            navKey = sbn.getKey();
            sendNav(sbn);
            return;
        }
        JSONObject o = NotifReader.toMessage(app, listener, sbn, messagesOnly());
        if (o == null) return;
        int sig = (o.optString("title") + "\n" + o.optString("text")).hashCode();
        Integer before = sent.put(sbn.getKey(), sig);
        if (before != null && before == sig) return;
        server.send(o);
    }

    void removed(StatusBarNotification sbn) {
        if (sbn == null) return;
        String key = sbn.getKey();
        if (key.equals(navKey)) {
            navKey = null;
            navPending = null;
            main.removeCallbacks(navLater);
            server.send(NotifReader.navEnded());
            return;
        }
        if (sent.remove(key) != null) server.send(Link.put(Link.msg("notif_gone"), "key", key));
    }

    /** Maps updates its notification many times a second; pass on at most about one a second. */
    private void sendNav(StatusBarNotification sbn) {
        long now = SystemClock.elapsedRealtime();
        if (now - navSentAt >= 1000) {
            navSentAt = now;
            navPending = null;
            server.send(NotifReader.nav(app, sbn));
        } else {
            navPending = sbn;
            main.removeCallbacks(navLater);
            main.postDelayed(navLater, 1000 - (now - navSentAt));
        }
    }

    private final Runnable navLater = this::flushNav;

    private void flushNav() {
        StatusBarNotification sbn = navPending;
        if (sbn != null && server.isConnected()) sendNav(sbn);
    }

    // ---- LinkServer.Events --------------------------------------------------------------------

    @Override
    public void onLinkReady(String transport) {
        holdAwake(true);
        watchBattery(true);
        fullSync();
        changed();
    }

    @Override
    public void onLinkGone() {
        holdAwake(false);
        watchBattery(false);
        changed();
    }

    @Override
    public void onCommand(JSONObject m) {
        String t = m.optString("t");
        if ("cmd".equals(t)) {
            media.command(m.optString("a"));
        } else if ("sync".equals(t)) {
            fullSync();
        } else if ("dismiss".equals(t)) {
            if (listener != null) {
                try {
                    listener.cancelNotification(m.optString("key"));
                } catch (Exception ignored) {
                }
            }
        } else if ("reply".equals(t)) {
            boolean ok = false;
            if (listener != null) {
                try {
                    StatusBarNotification[] found = listener.getActiveNotifications(new String[]{m.optString("key")});
                    if (found != null && found.length > 0) ok = NotifReader.reply(app, found[0], m.optString("text"));
                } catch (Exception ignored) {
                }
            }
            server.send(Link.put(Link.msg("reply_result"), "ok", ok));
        }
    }

    /** Send the display everything it needs to draw the current picture. */
    private void fullSync() {
        lastArtId = "";
        lastBattery = -1;
        sent.clear();
        navKey = null;
        server.send(Link.msg("reset"));
        sendBattery(app.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED)));
        media.start();
        media.publishNow();
        boolean navFound = false;
        if (listener != null) {
            try {
                StatusBarNotification[] all = listener.getActiveNotifications();
                if (all != null) {
                    for (StatusBarNotification sbn : all) {
                        if (NotifReader.isNav(sbn)) navFound = true;
                        posted(sbn);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        if (!navFound) server.send(NotifReader.navEnded());
        server.send(Link.msg("synced"));
    }

    void sendTest() {
        JSONObject o = Link.msg("notif");
        Link.put(o, "key", "test-" + System.currentTimeMillis());
        Link.put(o, "pkg", app.getPackageName());
        Link.put(o, "app", "Car Dash");
        Link.put(o, "title", "Test message");
        Link.put(o, "text", "The phone and the display are talking to each other.");
        Link.put(o, "when", System.currentTimeMillis());
        Link.put(o, "canReply", false);
        server.send(o);
    }

    // ---- MediaWatcher.Events ------------------------------------------------------------------

    @Override
    public void onMedia(JSONObject state, Bitmap art, String artId) {
        if (!server.isConnected()) return;
        server.send(state);
        if (art != null && !artId.isEmpty() && !artId.equals(lastArtId)) {
            String b64 = encodeArt(art);
            if (b64 != null) {
                lastArtId = artId;
                JSONObject o = Link.msg("art");
                Link.put(o, "id", artId);
                Link.put(o, "b64", b64);
                server.send(o);
            }
        }
    }

    private static String encodeArt(Bitmap src) {
        try {
            Bitmap soft = src.getConfig() == Bitmap.Config.HARDWARE ? src.copy(Bitmap.Config.ARGB_8888, false) : src;
            if (soft == null) return null;
            int max = 320;
            int w = soft.getWidth();
            int h = soft.getHeight();
            if (w <= 0 || h <= 0) return null;
            float k = Math.min(1f, max / (float) Math.max(w, h));
            Bitmap small = k < 1f
                    ? Bitmap.createScaledBitmap(soft, Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)), true)
                    : soft;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            small.compress(Bitmap.CompressFormat.JPEG, 80, bos);
            return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    // ---- Battery and staying awake --------------------------------------------------------------

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            sendBattery(intent);
        }
    };

    private void watchBattery(boolean on) {
        if (on == batteryWatching) return;
        batteryWatching = on;
        try {
            IntentFilter f = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            if (!on) app.unregisterReceiver(batteryReceiver);
            else if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(batteryReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            else app.registerReceiver(batteryReceiver, f);
        } catch (Exception ignored) {
        }
    }

    private void sendBattery(Intent i) {
        if (i == null) return;
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        if (level < 0 || scale <= 0) return;
        int pct = Math.round(level * 100f / scale);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
        if (pct == lastBattery && charging == lastCharging) return;
        lastBattery = pct;
        lastCharging = charging;
        JSONObject o = Link.msg("phone");
        Link.put(o, "battery", pct);
        Link.put(o, "charging", charging);
        server.send(o);
    }

    /** Keep the phone's processor awake while a display is connected, so the link does not stall. */
    private void holdAwake(boolean on) {
        try {
            if (wake == null) {
                PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
                if (pm == null) return;
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cardash:link");
                wake.setReferenceCounted(false);
            }
            if (on) wake.acquire();
            else if (wake.isHeld()) wake.release();
        } catch (Exception ignored) {
        }
    }
}
