package com.cardash.display;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;

import org.json.JSONObject;

/**
 * Runs for as long as the display is on. Keeps the connection to the phone alive, even while
 * another app (for example Google Maps) is on screen, and opens the dashboard when the car starts.
 */
public class LinkService extends Service implements LinkClient.Events {

    static final String EXTRA_OPEN = "open";
    private static final String CHANNEL = "link";
    private static volatile LinkService instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private LinkClient client;
    private Banner banner;
    private boolean screenWatching;
    private String noteText = "";

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Many car displays sleep instead of switching off, so "screen on" is our "car started".
            if (client != null) client.kick();
            openDashboardIfAllowed();
        }
    };

    /** Start the service if it is not running yet. */
    static void ensure(Context c) {
        try {
            c.startForegroundService(new Intent(c, LinkService.class));
        } catch (Exception ignored) {
        }
    }

    static boolean send(JSONObject o) {
        LinkService s = instance;
        return s != null && s.client != null && s.client.send(o);
    }

    static void kick() {
        LinkService s = instance;
        if (s != null && s.client != null) s.client.kick();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Phone connection", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        if (!goForeground("Looking for your phone…")) {
            stopSelf();
            return;
        }
        banner = new Banner(this);
        client = new LinkClient(getApplicationContext(), main, this);
        client.start();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screenReceiver, f);
        screenWatching = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!goForeground(noteText.isEmpty() ? "Looking for your phone…" : noteText)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && intent.getBooleanExtra(EXTRA_OPEN, false)) openDashboardIfAllowed();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (screenWatching) {
            try {
                unregisterReceiver(screenReceiver);
            } catch (Exception ignored) {
            }
        }
        if (client != null) client.stop();
        if (banner != null) banner.hide();
        if (instance == this) instance = null;
        DashState.I.setLink(false, "", "", "Car Dash is not running.");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---- LinkClient.Events ----------------------------------------------------------------------

    @Override
    public void onStatus(boolean connected, String transport, String phoneName, String hint) {
        DashState.I.setLink(connected, transport, phoneName, hint);
        String text = connected
                ? "Connected to " + (phoneName.isEmpty() ? "your phone" : phoneName)
                : "Looking for your phone…";
        if (!text.equals(noteText)) {
            noteText = text;
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                try {
                    nm.notify(1, note(text));
                } catch (Exception ignored) {
                }
            }
        }
    }

    @Override
    public void onMessage(JSONObject m) {
        if ("reply_result".equals(m.optString("t"))) {
            android.widget.Toast.makeText(this, m.optBoolean("ok") ? "Reply sent" : "Could not send the reply",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        DashState.Notif fresh = DashState.I.apply(m);
        if (fresh != null && !DashboardActivity.visible && banner != null
                && Prefs.flag(this, Prefs.BANNER, true) && Settings.canDrawOverlays(this)) {
            banner.show(fresh);
        }
    }

    // ---- Helpers --------------------------------------------------------------------------------

    private void openDashboardIfAllowed() {
        if (!Prefs.flag(this, Prefs.AUTO_OPEN, true) || DashboardActivity.visible) return;
        // Android only lets a background app open a screen if it may "display over other apps".
        if (!Settings.canDrawOverlays(this)) return;
        try {
            startActivity(new Intent(this, DashboardActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
        }
    }

    private boolean goForeground(String text) {
        Notification n = note(text);
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(1, n);
            }
            return true;
        } catch (Exception e) {
            try {
                startForeground(1, n);
                return true;
            } catch (Exception e2) {
                return false;
            }
        }
    }

    private Notification note(String text) {
        noteText = text;
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, DashboardActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("Car Dash")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(open)
                .build();
    }
}
