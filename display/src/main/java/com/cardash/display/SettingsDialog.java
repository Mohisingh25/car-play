package com.cardash.display;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.cardash.link.Ui;

import java.util.ArrayList;
import java.util.List;

/** The display app's settings screen. */
final class SettingsDialog extends Dialog {

    private final Activity a;
    private final float u;
    private TextView statusText;
    private TextView phoneText;
    private TextView overlayText;

    SettingsDialog(Activity a) {
        super(a, android.R.style.Theme_Material_NoActionBar);
        this.a = a;
        DisplayMetrics dm = a.getResources().getDisplayMetrics();
        this.u = Math.min(dm.widthPixels, dm.heightPixels) / 100f;
        build();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) refresh();
    }

    private int px(float units) {
        return Math.round(units * u);
    }

    private void build() {
        int pad = px(3);
        LinearLayout root = Ui.col(a);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout top = Ui.row(a);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(Ui.text(a, "Settings", 5f * u, Ui.TEXT, true), Ui.lp(0, Ui.WRAP, 1f));
        ImageView close = new ImageView(a);
        close.setImageDrawable(new Icons(Icons.CLOSE, Ui.TEXT));
        close.setPadding(px(2.6f), px(2.6f), px(2.6f), px(2.6f));
        close.setBackground(Ui.pressable(Ui.CARD, 2.5f * u));
        close.setOnClickListener(v -> dismiss());
        top.addView(close, Ui.lp(px(10), px(10)));
        root.addView(top, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 0, 0, pad));

        // Connection
        LinearLayout link = card();
        link.addView(Ui.text(a, "Phone connection", 3.6f * u, Ui.TEXT, true));
        statusText = Ui.text(a, "", 3f * u, Ui.MUTED, false);
        statusText.setPadding(0, px(1.2f), 0, px(2));
        statusText.setLineSpacing(0.6f * u, 1f);
        link.addView(statusText);
        LinearLayout phoneRow = Ui.row(a);
        phoneRow.setGravity(Gravity.CENTER_VERTICAL);
        phoneText = Ui.text(a, "", 3.2f * u, Ui.TEXT, false);
        phoneRow.addView(phoneText, Ui.lp(0, Ui.WRAP, 1f));
        phoneRow.addView(button("Choose phone", false, v -> choosePhone()));
        link.addView(phoneRow);
        root.addView(link);

        // Behaviour
        root.addView(toggle("Open when the car starts",
                "Car Dash comes to the front by itself when the display switches on.",
                Prefs.flag(a, Prefs.AUTO_OPEN, true), on -> {
                    Prefs.set(a, Prefs.AUTO_OPEN, on);
                    if (on) needOverlay();
                }));
        root.addView(toggle("Show new messages over other apps",
                "A small pop-up at the top of the screen, for example while Maps is open.",
                Prefs.flag(a, Prefs.BANNER, true), on -> {
                    Prefs.set(a, Prefs.BANNER, on);
                    if (on) needOverlay();
                }));

        LinearLayout overlay = card();
        overlayText = Ui.text(a, "", 3f * u, Ui.WARN, false);
        overlayText.setLineSpacing(0.6f * u, 1f);
        overlay.addView(overlayText);
        overlay.addView(Ui.gap(a, 1, px(1.5f)));
        overlay.addView(button("Allow \"Display over other apps\"", true, v -> openOverlaySettings()),
                Ui.lp(Ui.WRAP, Ui.WRAP));
        overlay.setTag("overlay");
        root.addView(overlay);

        root.addView(toggle("Use Car Dash as the home screen",
                "The most reliable way to always land on Car Dash. Your other apps stay available under the Apps button.",
                homeEnabled(), this::setHome));

        // Wi-Fi backup
        LinearLayout wifi = toggle("Wi-Fi backup",
                "Use this if Bluetooth does not connect. Turn on the phone's hotspot, connect this display to it, "
                        + "switch on \"Wi-Fi backup\" in the phone app and enter the code it shows.",
                Prefs.flag(a, Prefs.WIFI_ON, false), on -> {
                    Prefs.set(a, Prefs.WIFI_ON, on);
                    LinkService.kick();
                });
        EditText code = field("6-digit code from the phone app", Prefs.text(a, Prefs.WIFI_CODE), true);
        EditText host = field("Phone address (leave empty for automatic)", Prefs.text(a, Prefs.WIFI_HOST), false);
        wifi.addView(code, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, px(2), 0, 0));
        wifi.addView(host, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, px(1.5f), 0, px(2)));
        wifi.addView(button("Save", true, v -> {
            String c = code.getText().toString().replaceAll("[^0-9]", "");
            Prefs.set(a, Prefs.WIFI_CODE, c);
            Prefs.set(a, Prefs.WIFI_HOST, host.getText().toString().trim());
            LinkService.kick();
            Toast.makeText(a, c.length() == 6 ? "Saved" : "The code should have 6 digits", Toast.LENGTH_SHORT).show();
        }), Ui.lp(Ui.WRAP, Ui.WRAP));
        root.addView(wifi);

        ScrollView scroll = new ScrollView(a);
        scroll.setBackgroundColor(Ui.BG);
        scroll.addView(root);
        setContentView(scroll);
        refresh();
    }

    private void refresh() {
        DashState s = DashState.I;
        if (s.connected) {
            statusText.setText("Connected to " + (s.phoneName.isEmpty() ? "your phone" : s.phoneName)
                    + ("wifi".equals(s.transport) ? " over Wi-Fi." : " over Bluetooth."));
            statusText.setTextColor(Ui.ACCENT);
        } else {
            statusText.setText(s.hint.isEmpty() ? "Looking for your phone…" : s.hint);
            statusText.setTextColor(Ui.MUTED);
        }
        String name = Prefs.text(a, Prefs.BT_NAME);
        phoneText.setText(Prefs.text(a, Prefs.BT_ADDR).isEmpty() ? "Phone: automatic" : "Phone: " + name);

        boolean wantsOverlay = Prefs.flag(a, Prefs.AUTO_OPEN, true) || Prefs.flag(a, Prefs.BANNER, true);
        boolean missing = wantsOverlay && !Settings.canDrawOverlays(a);
        View overlayCard = findViewByTag("overlay");
        if (overlayCard != null) overlayCard.setVisibility(missing ? View.VISIBLE : View.GONE);
        overlayText.setText("Opening by itself and the message pop-up both need the "
                + "\"Display over other apps\" permission. It is not allowed yet.");
    }

    private View findViewByTag(String tag) {
        View content = findViewById(android.R.id.content);
        return content == null ? null : content.findViewWithTag(tag);
    }

    // ---- Actions --------------------------------------------------------------------------------

    private void needOverlay() {
        if (!Settings.canDrawOverlays(a)) openOverlaySettings();
        refresh();
    }

    private void openOverlaySettings() {
        try {
            a.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + a.getPackageName())));
        } catch (Exception e) {
            Toast.makeText(a, "Open this display's Settings > Apps > Car Dash and allow \"Display over other apps\".",
                    Toast.LENGTH_LONG).show();
        }
    }

    private ComponentName homeAlias() {
        return new ComponentName(a.getPackageName(), "com.cardash.display.HomeAlias");
    }

    private boolean homeEnabled() {
        return a.getPackageManager().getComponentEnabledSetting(homeAlias())
                == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
    }

    private void setHome(boolean on) {
        a.getPackageManager().setComponentEnabledSetting(homeAlias(),
                on ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
        if (!on) return;
        try {
            a.startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
        } catch (Exception e) {
            Toast.makeText(a, "Now press the Home button and choose Car Dash, then \"Always\".", Toast.LENGTH_LONG).show();
        }
    }

    private void choosePhone() {
        if (!LinkClient.hasBtPermission(a)) {
            Toast.makeText(a, "Allow the \"Nearby devices\" permission first.", Toast.LENGTH_SHORT).show();
            return;
        }
        List<String> labels = new ArrayList<>();
        List<String> addresses = new ArrayList<>();
        labels.add("Automatic (any paired phone)");
        addresses.add("");
        try {
            BluetoothAdapter adapter = LinkClient.adapter(a);
            if (adapter != null && adapter.getBondedDevices() != null) {
                for (BluetoothDevice d : adapter.getBondedDevices()) {
                    String n = d.getName();
                    labels.add(n == null || n.isEmpty() ? d.getAddress() : n);
                    addresses.add(d.getAddress());
                }
            }
        } catch (Exception ignored) {
        }
        new AlertDialog.Builder(a)
                .setTitle("Which phone runs the Car Dash Companion app?")
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    Prefs.set(a, Prefs.BT_ADDR, addresses.get(which));
                    Prefs.set(a, Prefs.BT_NAME, which == 0 ? "" : labels.get(which));
                    LinkService.kick();
                    refresh();
                })
                .show();
    }

    // ---- Small view builders ----------------------------------------------------------------------

    private interface OnToggle {
        void set(boolean on);
    }

    private LinearLayout card() {
        LinearLayout c = Ui.col(a);
        c.setBackground(Ui.round(Ui.CARD, 2.5f * u));
        c.setPadding(px(3), px(3), px(3), px(3));
        c.setLayoutParams(Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 0, 0, px(2)));
        return c;
    }

    private LinearLayout toggle(String title, String help, boolean initial, OnToggle onToggle) {
        LinearLayout c = card();
        LinearLayout top = Ui.row(a);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(Ui.text(a, title, 3.6f * u, Ui.TEXT, true), Ui.lp(0, Ui.WRAP, 1f));
        Switch sw = new Switch(a);
        sw.setChecked(initial);
        sw.setOnCheckedChangeListener((button, on) -> onToggle.set(on));
        top.addView(sw);
        c.addView(top);
        TextView h = Ui.text(a, help, 3f * u, Ui.MUTED, false);
        h.setLineSpacing(0.6f * u, 1f);
        h.setPadding(0, px(1.2f), 0, 0);
        c.addView(h);
        return c;
    }

    private TextView button(String label, boolean filled, View.OnClickListener onClick) {
        TextView b = Ui.text(a, label, 3.2f * u, filled ? Ui.ON_ACCENT : Ui.ACCENT, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(px(3.5f), px(2.2f), px(3.5f), px(2.2f));
        b.setBackground(Ui.pressable(filled ? Ui.ACCENT : Ui.CARD_HI, 2f * u));
        b.setOnClickListener(onClick);
        return b;
    }

    private EditText field(String hint, String value, boolean digits) {
        EditText e = new EditText(a);
        e.setHint(hint);
        e.setText(value);
        e.setSingleLine(true);
        e.setTextSize(TypedValue.COMPLEX_UNIT_PX, 3.4f * u);
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.MUTED);
        e.setBackground(Ui.round(Ui.CARD_HI, 2f * u));
        e.setPadding(px(2.5f), px(2.2f), px(2.5f), px(2.2f));
        e.setInputType(digits ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        return e;
    }
}
