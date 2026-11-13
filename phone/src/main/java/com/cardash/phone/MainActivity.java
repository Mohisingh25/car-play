package com.cardash.phone;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.cardash.link.Ui;

/** The phone app's only screen: a short setup checklist and a few options. */
public class MainActivity extends Activity {

    private Hub hub;
    private TextView statusTitle;
    private TextView statusDetail;
    private View statusDot;
    private TextView accessState;
    private TextView btState;
    private TextView codeText;
    private final Runnable onHubChanged = () -> runOnUiThread(this::refresh);
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            statusDot.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        hub = Hub.get(this);
        hub.start();
        build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hub.watch(onHubChanged);
        // After an update Android sometimes forgets to restart the listener; ask it to.
        if (listenerEnabled(this) && !hub.listenerBound()) {
            try {
                NotificationListenerService.requestRebind(new ComponentName(this, PhoneListenerService.class));
            } catch (Exception ignored) {
            }
        }
        statusDot.removeCallbacks(tick);
        tick.run();
    }

    @Override
    protected void onPause() {
        statusDot.removeCallbacks(tick);
        hub.unwatch(onHubChanged);
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refresh();
    }

    // ---- Screen -------------------------------------------------------------------------------

    private void build() {
        int pad = Ui.dp(this, 20);
        float r = Ui.dp(this, 16);

        LinearLayout root = Ui.col(this);
        root.setPadding(pad, pad + Ui.dp(this, 24), pad, pad);

        root.addView(Ui.text(this, "Car Dash Companion", Ui.dp(this, 24), Ui.TEXT, true));
        TextView intro = Ui.text(this, "Sends your music, messages and directions to the Car Dash app on your car display.",
                Ui.dp(this, 14), Ui.MUTED, false);
        intro.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 18));
        root.addView(intro);

        // Connection status
        LinearLayout status = Ui.row(this);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setBackground(Ui.round(Ui.CARD, r));
        status.setPadding(pad, pad, pad, pad);
        statusDot = new View(this);
        status.addView(statusDot, Ui.margins(Ui.lp(Ui.dp(this, 14), Ui.dp(this, 14)), 0, 0, Ui.dp(this, 14), 0));
        LinearLayout statusTexts = Ui.col(this);
        statusTitle = Ui.text(this, "", Ui.dp(this, 17), Ui.TEXT, true);
        statusDetail = Ui.text(this, "", Ui.dp(this, 13), Ui.MUTED, false);
        statusDetail.setPadding(0, Ui.dp(this, 4), 0, 0);
        statusTexts.addView(statusTitle);
        statusTexts.addView(statusDetail);
        status.addView(statusTexts, Ui.lp(0, Ui.WRAP, 1f));
        root.addView(status, Ui.lp(Ui.MATCH, Ui.WRAP));

        root.addView(heading("Setup"));

        // 1. Notification access
        accessState = Ui.text(this, "", Ui.dp(this, 13), Ui.MUTED, false);
        root.addView(step("1. Notification access",
                "Lets the app see your messages and control the music. If the switch is greyed out, open this app's "
                        + "App info page, tap the three dots at the top right and choose \"Allow restricted settings\".",
                accessState, "Open settings", v -> open(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))));

        // 2. Bluetooth permission (Android 12 and newer)
        btState = Ui.text(this, "", Ui.dp(this, 13), Ui.MUTED, false);
        if (Build.VERSION.SDK_INT >= 31) {
            root.addView(step("2. Nearby devices permission", "Needed to talk to the display over Bluetooth.",
                    btState, "Allow", v -> requestPermissions(new String[]{"android.permission.BLUETOOTH_CONNECT"}, 1)));
        }

        // 3. Pairing
        root.addView(step((Build.VERSION.SDK_INT >= 31 ? "3" : "2") + ". Pair with the car display",
                "Pair this phone with the display in Bluetooth settings, the same way you do for music and calls.",
                null, "Bluetooth settings", v -> open(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS))));

        root.addView(heading("Options"));

        root.addView(toggle("Messages only",
                "Send only chats and messages (WhatsApp, SMS and similar), not every app alert.",
                hub.messagesOnly(), on -> hub.setMessagesOnly(on)));

        LinearLayout wifi = toggle("Wi-Fi backup",
                "For displays whose Bluetooth cannot be used by apps. Turn on this phone's hotspot, connect the display "
                        + "to it, and enter the code below in the display app's settings. Use it only with your own hotspot.",
                hub.wifiEnabled(), on -> hub.setWifiEnabled(on));
        LinearLayout codeRow = Ui.row(this);
        codeRow.setGravity(Gravity.CENTER_VERTICAL);
        codeRow.setPadding(0, Ui.dp(this, 12), 0, 0);
        codeText = Ui.text(this, "", Ui.dp(this, 26), Ui.ACCENT, true);
        codeRow.addView(codeText, Ui.lp(0, Ui.WRAP, 1f));
        codeRow.addView(button("New code", false, v -> {
            hub.newCode();
            refresh();
        }));
        wifi.addView(codeRow);
        root.addView(wifi);

        TextView test = button("Send a test message to the display", true, v -> {
            if (hub.connected()) {
                hub.sendTest();
                Toast.makeText(this, "Sent. Check the display.", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "The display is not connected yet.", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(test, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 22), 0, 0));

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        scroll.addView(root);
        setContentView(scroll);
    }

    private TextView heading(String s) {
        TextView t = Ui.text(this, s.toUpperCase(), Ui.dp(this, 12), Ui.MUTED, true);
        t.setLetterSpacing(0.08f);
        t.setPadding(Ui.dp(this, 4), Ui.dp(this, 26), 0, Ui.dp(this, 10));
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = Ui.col(this);
        int pad = Ui.dp(this, 18);
        c.setBackground(Ui.round(Ui.CARD, Ui.dp(this, 16)));
        c.setPadding(pad, pad, pad, pad);
        c.setLayoutParams(Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 0, 0, Ui.dp(this, 10)));
        return c;
    }

    private LinearLayout step(String title, String help, TextView state, String action, View.OnClickListener onClick) {
        LinearLayout c = card();
        c.addView(Ui.text(this, title, Ui.dp(this, 16), Ui.TEXT, true));
        TextView h = Ui.text(this, help, Ui.dp(this, 13), Ui.MUTED, false);
        h.setLineSpacing(Ui.dp(this, 3), 1f);
        h.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 12));
        c.addView(h);
        LinearLayout bottom = Ui.row(this);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        if (state != null) bottom.addView(state, Ui.lp(0, Ui.WRAP, 1f));
        else bottom.addView(Ui.spring(this));
        bottom.addView(button(action, false, onClick));
        c.addView(bottom);
        return c;
    }

    private interface OnToggle {
        void set(boolean on);
    }

    private LinearLayout toggle(String title, String help, boolean initial, OnToggle onToggle) {
        LinearLayout c = card();
        LinearLayout top = Ui.row(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(Ui.text(this, title, Ui.dp(this, 16), Ui.TEXT, true), Ui.lp(0, Ui.WRAP, 1f));
        Switch sw = new Switch(this);
        sw.setChecked(initial);
        sw.setOnCheckedChangeListener((button, on) -> onToggle.set(on));
        top.addView(sw);
        c.addView(top);
        TextView h = Ui.text(this, help, Ui.dp(this, 13), Ui.MUTED, false);
        h.setLineSpacing(Ui.dp(this, 3), 1f);
        h.setPadding(0, Ui.dp(this, 6), 0, 0);
        c.addView(h);
        return c;
    }

    private TextView button(String label, boolean filled, View.OnClickListener onClick) {
        TextView b = Ui.text(this, label, Ui.dp(this, 14), filled ? Ui.ON_ACCENT : Ui.ACCENT, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(Ui.dp(this, 18), Ui.dp(this, 12), Ui.dp(this, 18), Ui.dp(this, 12));
        b.setBackground(Ui.pressable(filled ? Ui.ACCENT : Ui.CARD_HI, Ui.dp(this, 12)));
        b.setOnClickListener(onClick);
        return b;
    }

    // ---- State --------------------------------------------------------------------------------

    private void refresh() {
        boolean access = listenerEnabled(this);
        boolean bt = Build.VERSION.SDK_INT < 31
                || checkSelfPermission("android.permission.BLUETOOTH_CONNECT") == PackageManager.PERMISSION_GRANTED;

        accessState.setText(access ? "On" : "Off");
        accessState.setTextColor(access ? Ui.ACCENT : Ui.WARN);
        btState.setText(bt ? "Allowed" : "Not allowed yet");
        btState.setTextColor(bt ? Ui.ACCENT : Ui.WARN);

        String code = hub.code();
        codeText.setText(code.substring(0, 3) + " " + code.substring(3));

        boolean connected = hub.connected();
        statusDot.setBackground(Ui.round(connected ? Ui.ACCENT : Ui.WARN, Ui.dp(this, 7)));
        if (connected) {
            statusTitle.setText("Connected to the car display");
            statusDetail.setText("wifi".equals(hub.transport()) ? "Over Wi-Fi" : "Over Bluetooth");
        } else {
            statusTitle.setText("Waiting for the car display");
            if (!access) statusDetail.setText("Turn on notification access first (step 1).");
            else if (!bt) statusDetail.setText("Allow the Nearby devices permission (step 2).");
            else statusDetail.setText("Bluetooth: " + hub.bluetoothStatus()
                    + (hub.wifiEnabled() ? " · Wi-Fi backup: on" : ""));
        }
    }

    static boolean listenerEnabled(Context c) {
        String flat = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        if (flat == null || flat.isEmpty()) return false;
        ComponentName me = new ComponentName(c, PhoneListenerService.class);
        for (String s : flat.split(":")) {
            ComponentName cn = ComponentName.unflattenFromString(s);
            if (me.equals(cn)) return true;
        }
        return false;
    }

    private void open(Intent i) {
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Could not open that settings page on this phone.", Toast.LENGTH_SHORT).show();
        }
    }
}
