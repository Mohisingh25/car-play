package com.cardash.display;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.text.format.DateFormat;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.cardash.link.Link;
import com.cardash.link.Ui;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** The dashboard: clock, music, next turn and messages, sized to fit any car display. */
public class DashboardActivity extends Activity implements DashState.Watcher {

    /** True while the dashboard is on screen (the service uses it to decide about pop-ups). */
    static volatile boolean visible;

    private static final int REQ_PERMISSIONS = 1;
    private static final int REQ_VOICE = 2;
    private static final String[] QUICK_REPLIES = {
            "OK", "Yes", "No", "I'm driving, will call you later", "On my way", "Call me"
    };

    private final Handler h = new Handler(Looper.getMainLooper());
    /** One unit = 1% of the shorter side of the screen, so the layout scales to any display. */
    private float u = 6f;
    private int ticks;

    private TextView clock;
    private TextView date;
    private View linkDot;
    private TextView linkText;

    private LinearLayout navCard;
    private ImageView navIcon;
    private TextView navTitle;
    private TextView navText;
    private TextView navSub;

    private ImageView art;
    private TextView mApp;
    private TextView mTitle;
    private TextView mArtist;
    private TextView mTime;
    private TextView hintText;
    private View barFill;
    private ImageView playButton;

    private TextView notifHeader;
    private LinearLayout notifList;

    private TextToSpeech tts;
    private boolean ttsReady;
    private String ttsPending;
    private String voiceReplyKey;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateClock();
            updateProgress();
            if (++ticks % 30 == 0) refreshNotifs();
            h.postDelayed(this, 1000);
        }
    };

    // ---- Lifecycle ------------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        LinkService.ensure(this);
        build();
        askPermissions();
        askOverlayOnce();
    }

    /** Opening by itself needs one extra permission that Android only grants from its own settings page. */
    private void askOverlayOnce() {
        if (Settings.canDrawOverlays(this) || Prefs.flag(this, "asked_overlay", false)) return;
        Prefs.set(this, "asked_overlay", true);
        new AlertDialog.Builder(this)
                .setTitle("Open by itself when the car starts?")
                .setMessage("To come up on its own, and to show new messages while Maps is open, Car Dash needs the "
                        + "\"Display over other apps\" permission. You can also allow it later in Settings.")
                .setPositiveButton("Allow", (dialog, which) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) {
                        toast("Allow it under this display's Settings > Apps > Car Dash");
                    }
                })
                .setNegativeButton("Not now", null)
                .show();
    }

    @Override
    public void onBackPressed() {
        // As the home screen there is nowhere to go back to.
        int state = getPackageManager().getComponentEnabledSetting(
                new ComponentName(getPackageName(), "com.cardash.display.HomeAlias"));
        if (state != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) super.onBackPressed();
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
        DashState.I.watchers.add(this);
        LinkService.ensure(this);
        refreshAll();
        h.removeCallbacks(tick);
        h.post(tick);
    }

    @Override
    protected void onStop() {
        visible = false;
        DashState.I.watchers.remove(this);
        h.removeCallbacks(tick);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            try {
                tts.shutdown();
            } catch (Exception ignored) {
            }
        }
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        build();
        refreshAll();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        LinkService.ensure(this);
        LinkService.kick();
    }

    private void askPermissions() {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission("android.permission.BLUETOOTH_CONNECT") != PackageManager.PERMISSION_GRANTED) {
            need.add("android.permission.BLUETOOTH_CONNECT");
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            need.add("android.permission.POST_NOTIFICATIONS");
        }
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_PERMISSIONS);
    }

    // ---- Layout ---------------------------------------------------------------------------------

    private int px(float units) {
        return Math.round(units * u);
    }

    private void build() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        u = Math.min(dm.widthPixels, dm.heightPixels) / 100f;
        boolean wide = dm.widthPixels >= dm.heightPixels;
        int pad = px(2.5f);

        LinearLayout root = wide ? Ui.row(this) : Ui.col(this);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(pad, pad, pad, pad);

        View rail = buildRail(wide);
        View media = buildMedia();
        navCard = buildNav();
        View notifs = buildNotifs();

        if (wide) {
            root.addView(rail, Ui.margins(Ui.lp(px(17), Ui.MATCH), 0, 0, pad, 0));
            LinearLayout left = Ui.col(this);
            left.addView(navCard, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 0, 0, pad));
            left.addView(media, Ui.lp(Ui.MATCH, 0, 1f));
            root.addView(left, Ui.margins(Ui.lp(0, Ui.MATCH, 5f), 0, 0, pad, 0));
            root.addView(notifs, Ui.lp(0, Ui.MATCH, 4f));
        } else {
            root.addView(rail, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 0, 0, pad));
            root.addView(navCard, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 0, 0, pad));
            root.addView(media, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 0, 0, pad));
            root.addView(notifs, Ui.lp(Ui.MATCH, 0, 1f));
        }
        setContentView(root);
    }

    /** Clock, the three main buttons and the phone status. A side rail on wide screens, a top bar on tall ones. */
    private View buildRail(boolean wide) {
        LinearLayout rail = wide ? Ui.col(this) : Ui.row(this);
        rail.setGravity(wide ? Gravity.CENTER_HORIZONTAL : Gravity.CENTER_VERTICAL);
        rail.setBackground(Ui.round(Ui.CARD, 2.5f * u));
        rail.setPadding(px(1.5f), px(2.5f), px(1.5f), px(2.5f));

        LinearLayout time = Ui.col(this);
        time.setGravity(wide ? Gravity.CENTER_HORIZONTAL : Gravity.START);
        clock = Ui.text(this, "", 4.6f * u, Ui.TEXT, true);
        date = Ui.lines(Ui.text(this, "", 2.4f * u, Ui.MUTED, false), 2);
        date.setGravity(wide ? Gravity.CENTER : Gravity.START);
        date.setPadding(0, px(0.8f), 0, 0);
        time.addView(clock);
        time.addView(date);
        rail.addView(time, Ui.lp(Ui.WRAP, Ui.WRAP));
        rail.addView(Ui.spring(this));

        int gap = px(1.2f);
        rail.addView(railButton(Icons.MAP, "Maps", v -> openMaps()),
                Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), gap, gap, gap, gap));
        rail.addView(railButton(Icons.APPS, "Apps", v -> AppsDialog.show(this)),
                Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), gap, gap, gap, gap));
        rail.addView(railButton(Icons.SETTINGS, "Settings", v -> new SettingsDialog(this).show()),
                Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), gap, gap, gap, gap));
        rail.addView(Ui.spring(this));

        LinearLayout link = Ui.col(this);
        link.setGravity(Gravity.CENTER_HORIZONTAL);
        linkDot = new View(this);
        link.addView(linkDot, Ui.lp(px(1.8f), px(1.8f)));
        linkText = Ui.text(this, "", 2.3f * u, Ui.MUTED, false);
        linkText.setGravity(Gravity.CENTER);
        Ui.lines(linkText, 2);
        linkText.setPadding(0, px(1), 0, 0);
        link.addView(linkText, Ui.lp(wide ? Ui.MATCH : Ui.WRAP, Ui.WRAP));
        rail.addView(link, Ui.lp(wide ? Ui.MATCH : Ui.WRAP, Ui.WRAP));
        return rail;
    }

    private View railButton(int icon, String label, View.OnClickListener onClick) {
        LinearLayout cell = Ui.col(this);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView b = new ImageView(this);
        b.setImageDrawable(new Icons(icon, Ui.TEXT));
        int p = px(2.8f);
        b.setPadding(p, p, p, p);
        b.setBackground(Ui.pressable(Ui.CARD_HI, 2.5f * u));
        b.setOnClickListener(onClick);
        cell.addView(b, Ui.lp(px(10.5f), px(10.5f)));
        TextView t = Ui.text(this, label, 2.2f * u, Ui.MUTED, false);
        t.setPadding(0, px(0.8f), 0, 0);
        cell.addView(t, Ui.lp(Ui.WRAP, Ui.WRAP));
        return cell;
    }

    private View buildMedia() {
        LinearLayout card = Ui.col(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(Ui.round(Ui.CARD, 2.5f * u));
        card.setPadding(px(3.5f), px(3), px(3.5f), px(3));

        LinearLayout top = Ui.row(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        art = new ImageView(this);
        art.setBackground(Ui.round(Ui.CARD_HI, 2f * u));
        art.setClipToOutline(true);
        top.addView(art, Ui.margins(Ui.lp(px(24), px(24)), 0, 0, px(3.5f), 0));

        LinearLayout texts = Ui.col(this);
        mApp = Ui.oneLine(Ui.text(this, "", 2.5f * u, Ui.ACCENT, true));
        mApp.setLetterSpacing(0.06f);
        mTitle = Ui.lines(Ui.text(this, "", 4.4f * u, Ui.TEXT, true), 2);
        mTitle.setPadding(0, px(1.2f), 0, 0);
        mArtist = Ui.oneLine(Ui.text(this, "", 3.3f * u, Ui.MUTED, false));
        mArtist.setPadding(0, px(1.2f), 0, 0);
        texts.addView(mApp);
        texts.addView(mTitle);
        texts.addView(mArtist);
        top.addView(texts, Ui.lp(0, Ui.WRAP, 1f));
        card.addView(top, Ui.lp(Ui.MATCH, Ui.WRAP));

        FrameLayout track = new FrameLayout(this);
        track.setBackground(Ui.round(Ui.LINE, px(0.5f)));
        barFill = new View(this);
        barFill.setBackground(Ui.round(Ui.ACCENT, px(0.5f)));
        barFill.setPivotX(0f);
        barFill.setScaleX(0f);
        track.addView(barFill, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        card.addView(track, Ui.margins(Ui.lp(Ui.MATCH, Math.max(4, px(1f))), 0, px(3.5f), 0, px(1.2f)));

        mTime = Ui.text(this, "", 2.4f * u, Ui.MUTED, false);
        card.addView(mTime, Ui.lp(Ui.MATCH, Ui.WRAP));

        LinearLayout controls = Ui.row(this);
        controls.setGravity(Gravity.CENTER);
        controls.addView(roundButton(Icons.PREV, false, px(12.5f), v -> command("prev")));
        playButton = roundButton(Icons.PLAY, true, px(15.5f), v -> command("toggle"));
        controls.addView(playButton, Ui.margins(Ui.lp(px(15.5f), px(15.5f)), px(6), 0, px(6), 0));
        controls.addView(roundButton(Icons.NEXT, false, px(12.5f), v -> command("next")));
        card.addView(controls, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, px(2), 0, 0));

        hintText = Ui.lines(Ui.text(this, "", 2.7f * u, Ui.WARN, false), 5);
        hintText.setLineSpacing(0.5f * u, 1f);
        hintText.setPadding(0, px(2.5f), 0, 0);
        card.addView(hintText, Ui.lp(Ui.MATCH, Ui.WRAP));
        return card;
    }

    private ImageView roundButton(int icon, boolean primary, int size, View.OnClickListener onClick) {
        ImageView v = new ImageView(this);
        v.setImageDrawable(new Icons(icon, primary ? Ui.ON_ACCENT : Ui.TEXT));
        int p = Math.round(size * 0.28f);
        v.setPadding(p, p, p, p);
        v.setBackground(Ui.pressable(primary ? Ui.ACCENT : Ui.CARD_HI, size / 2f));
        v.setOnClickListener(onClick);
        v.setLayoutParams(Ui.lp(size, size));
        return v;
    }

    private LinearLayout buildNav() {
        LinearLayout card = Ui.row(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(Ui.roundStroke(Ui.CARD, 2.5f * u, Ui.ACCENT, Math.max(2, px(0.3f))));
        card.setPadding(px(3), px(2.5f), px(3), px(2.5f));
        navIcon = new ImageView(this);
        card.addView(navIcon, Ui.margins(Ui.lp(px(11), px(11)), 0, 0, px(3), 0));
        LinearLayout texts = Ui.col(this);
        navTitle = Ui.oneLine(Ui.text(this, "", 5f * u, Ui.ACCENT, true));
        navText = Ui.lines(Ui.text(this, "", 3.4f * u, Ui.TEXT, false), 2);
        navText.setPadding(0, px(0.8f), 0, 0);
        navSub = Ui.oneLine(Ui.text(this, "", 2.6f * u, Ui.MUTED, false));
        navSub.setPadding(0, px(0.8f), 0, 0);
        texts.addView(navTitle);
        texts.addView(navText);
        texts.addView(navSub);
        card.addView(texts, Ui.lp(0, Ui.WRAP, 1f));
        card.setOnClickListener(v -> openMaps());
        card.setVisibility(View.GONE);
        return card;
    }

    private View buildNotifs() {
        LinearLayout box = Ui.col(this);
        notifHeader = Ui.text(this, "MESSAGES", 2.5f * u, Ui.MUTED, true);
        notifHeader.setLetterSpacing(0.08f);
        notifHeader.setPadding(px(1), px(1), 0, px(2));
        box.addView(notifHeader);
        notifList = Ui.col(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(notifList);
        box.addView(scroll, Ui.lp(Ui.MATCH, 0, 1f));
        return box;
    }

    private View notifView(DashState.Notif n) {
        LinearLayout card = Ui.col(this);
        card.setBackground(Ui.round(Ui.CARD, 2.5f * u));
        card.setPadding(px(2.8f), px(2.4f), px(2.8f), px(2.4f));
        card.setLayoutParams(Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 0, 0, px(1.6f)));

        LinearLayout top = Ui.row(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(Ui.oneLine(Ui.text(this, n.app.toUpperCase(), 2.3f * u, Ui.ACCENT, true)), Ui.lp(0, Ui.WRAP, 1f));
        top.addView(Ui.text(this, age(n.when), 2.3f * u, Ui.MUTED, false));
        card.addView(top);

        if (!n.title.isEmpty()) {
            TextView title = Ui.oneLine(Ui.text(this, n.title, 3.5f * u, Ui.TEXT, true));
            title.setPadding(0, px(1.2f), 0, 0);
            card.addView(title);
        }
        if (!n.text.isEmpty()) {
            TextView body = Ui.lines(Ui.text(this, n.text, 3.1f * u, 0xFFC9D2DC, false), 3);
            body.setLineSpacing(0.5f * u, 1f);
            body.setPadding(0, px(0.9f), 0, 0);
            card.addView(body);
        }

        LinearLayout actions = Ui.row(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.addView(pill(Icons.SPEAK, "Read", v -> speak(n.title + ". " + n.text)));
        if (n.canReply) {
            actions.addView(Ui.gap(this, px(1.5f), 1));
            actions.addView(pill(Icons.REPLY, "Reply", v -> replyDialog(n)));
        }
        actions.addView(Ui.spring(this));
        actions.addView(pill(Icons.CLOSE, null, v -> {
            LinkService.send(Link.put(Link.msg("dismiss"), "key", n.key));
            DashState.I.remove(n.key);
        }));
        card.addView(actions, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, px(1.8f), 0, 0));
        return card;
    }

    private View pill(int icon, String label, View.OnClickListener onClick) {
        LinearLayout p = Ui.row(this);
        p.setGravity(Gravity.CENTER);
        p.setBackground(Ui.pressable(Ui.CARD_HI, 2f * u));
        p.setPadding(px(2.4f), 0, px(2.4f), 0);
        p.setMinimumHeight(px(7.5f));
        p.setMinimumWidth(px(7.5f));
        ImageView i = new ImageView(this);
        i.setImageDrawable(new Icons(icon, Ui.TEXT));
        p.addView(i, Ui.lp(px(3.6f), px(3.6f)));
        if (label != null) {
            TextView t = Ui.text(this, label, 2.8f * u, Ui.TEXT, true);
            t.setPadding(px(1.4f), 0, 0, 0);
            p.addView(t);
        }
        p.setOnClickListener(onClick);
        return p;
    }

    // ---- Showing the current state --------------------------------------------------------------

    @Override
    public void onChanged(int what) {
        if ((what & (DashState.LINK | DashState.PHONE)) != 0) refreshLink();
        if ((what & (DashState.MEDIA | DashState.LINK)) != 0) refreshMedia();
        if ((what & DashState.NAV) != 0) refreshNav();
        if ((what & DashState.NOTIFS) != 0) refreshNotifs();
    }

    private void refreshAll() {
        updateClock();
        refreshLink();
        refreshMedia();
        refreshNav();
        refreshNotifs();
    }

    private void updateClock() {
        Date now = new Date(System.currentTimeMillis() + DashState.I.clockOffset);
        boolean h24 = DateFormat.is24HourFormat(this);
        clock.setText(new SimpleDateFormat(h24 ? "HH:mm" : "h:mm", Locale.getDefault()).format(now));
        date.setText(new SimpleDateFormat(h24 ? "EEE d MMM" : "a, EEE d MMM", Locale.getDefault()).format(now));
    }

    private void refreshLink() {
        DashState s = DashState.I;
        linkDot.setBackground(Ui.round(s.connected ? Ui.ACCENT : Ui.WARN, px(0.9f)));
        if (s.connected) {
            String name = s.phoneName.isEmpty() ? "Phone" : s.phoneName;
            linkText.setText(s.battery >= 0 ? name + "\n" + s.battery + "%" : name);
        } else {
            linkText.setText("No phone");
        }
    }

    private void refreshMedia() {
        DashState s = DashState.I;
        boolean empty = "none".equals(s.mState) || (s.mTitle.isEmpty() && s.mArtist.isEmpty());
        mApp.setText(s.mApp.isEmpty() ? "MUSIC" : s.mApp.toUpperCase());
        if (!s.connected) {
            mTitle.setText("Waiting for your phone");
            mArtist.setText("Music and messages appear here once it connects");
        } else if (empty) {
            mTitle.setText("Nothing playing");
            mArtist.setText("Press play to start your music");
        } else {
            mTitle.setText(s.mTitle);
            mArtist.setText(s.mArtist.isEmpty() ? s.mAlbum : s.mArtist);
        }
        if (s.art != null && s.connected && !empty) {
            art.setPadding(0, 0, 0, 0);
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            art.setImageBitmap(s.art);
        } else {
            int p = px(6);
            art.setPadding(p, p, p, p);
            art.setScaleType(ImageView.ScaleType.FIT_CENTER);
            art.setImageDrawable(new Icons(Icons.NOTE, Ui.MUTED));
        }
        boolean playing = "playing".equals(s.mState) || "buffering".equals(s.mState);
        playButton.setImageDrawable(new Icons(playing ? Icons.PAUSE : Icons.PLAY, Ui.ON_ACCENT));
        hintText.setVisibility(s.connected || s.hint.isEmpty() ? View.GONE : View.VISIBLE);
        hintText.setText(s.hint);
        updateProgress();
    }

    private void updateProgress() {
        DashState s = DashState.I;
        long pos = s.position();
        float f = s.mDur > 0 ? Math.min(1f, pos / (float) s.mDur) : 0f;
        barFill.setScaleX(s.connected ? f : 0f);
        mTime.setText(s.connected && s.mDur > 0 ? clockTime(pos) + " / " + clockTime(s.mDur) : "");
    }

    private void refreshNav() {
        DashState s = DashState.I;
        boolean show = s.navActive && s.connected && !(s.navTitle.isEmpty() && s.navText.isEmpty());
        navCard.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        navTitle.setText(s.navTitle.isEmpty() ? s.navText : s.navTitle);
        navText.setText(s.navTitle.isEmpty() ? "" : s.navText);
        navText.setVisibility(navText.getText().length() == 0 ? View.GONE : View.VISIBLE);
        navSub.setText(s.navSub);
        navSub.setVisibility(s.navSub.isEmpty() ? View.GONE : View.VISIBLE);
        if (s.navIcon != null) navIcon.setImageBitmap(s.navIcon);
        else navIcon.setImageDrawable(new Icons(Icons.MAP, Ui.ACCENT));
    }

    private void refreshNotifs() {
        DashState s = DashState.I;
        notifList.removeAllViews();
        notifHeader.setText(s.notifs.isEmpty() ? "MESSAGES" : "MESSAGES · " + s.notifs.size());
        if (s.notifs.isEmpty()) {
            TextView empty = Ui.text(this, s.connected ? "No new messages" : "Messages from your phone appear here",
                    3f * u, Ui.MUTED, false);
            empty.setPadding(px(1), px(2), px(1), 0);
            notifList.addView(empty);
            return;
        }
        for (DashState.Notif n : s.notifs) notifList.addView(notifView(n));
    }

    private static String clockTime(long ms) {
        long total = ms / 1000;
        long hours = total / 3600;
        long mins = (total % 3600) / 60;
        long secs = total % 60;
        return hours > 0
                ? String.format(Locale.US, "%d:%02d:%02d", hours, mins, secs)
                : String.format(Locale.US, "%d:%02d", mins, secs);
    }

    private static String age(long when) {
        long d = System.currentTimeMillis() + DashState.I.clockOffset - when;
        if (when <= 0 || d < 60000L) return "now";
        if (d < 3600000L) return (d / 60000L) + " min";
        if (d < 86400000L) return (d / 3600000L) + " h";
        return (d / 86400000L) + " d";
    }

    // ---- Actions --------------------------------------------------------------------------------

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private void command(String action) {
        if (!LinkService.send(Link.put(Link.msg("cmd"), "a", action))) toast("Your phone is not connected");
    }

    private void openMaps() {
        Intent i = getPackageManager().getLaunchIntentForPackage("com.google.android.apps.maps");
        if (i == null) i = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0"));
        try {
            startActivity(i);
        } catch (Exception e) {
            toast("No maps app found on this display");
        }
    }

    private void speak(String text) {
        if (tts == null) {
            ttsPending = text;
            tts = new TextToSpeech(getApplicationContext(), status -> {
                ttsReady = status == TextToSpeech.SUCCESS;
                if (ttsReady && ttsPending != null) say(ttsPending);
                else if (!ttsReady) toast("This display has no text-to-speech voice installed");
                ttsPending = null;
            });
            return;
        }
        if (ttsReady) say(text);
        else ttsPending = text;
    }

    private void say(String text) {
        try {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "cardash");
        } catch (Exception ignored) {
        }
    }

    private Intent voiceIntent(String prompt) {
        return new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt);
    }

    private void replyDialog(DashState.Notif n) {
        final boolean voice = voiceIntent("").resolveActivity(getPackageManager()) != null;
        final List<String> options = new ArrayList<>();
        if (voice) options.add("Speak a reply…");
        options.addAll(Arrays.asList(QUICK_REPLIES));
        new AlertDialog.Builder(this)
                .setTitle("Reply to " + n.title)
                .setItems(options.toArray(new String[0]), (dialog, which) -> {
                    if (voice && which == 0) {
                        voiceReplyKey = n.key;
                        try {
                            startActivityForResult(voiceIntent("Reply to " + n.title), REQ_VOICE);
                        } catch (Exception e) {
                            toast("Voice input is not available on this display");
                        }
                    } else {
                        sendReply(n.key, options.get(which));
                    }
                })
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_VOICE || resultCode != RESULT_OK || data == null || voiceReplyKey == null) return;
        ArrayList<String> heard = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (heard == null || heard.isEmpty()) return;
        final String text = heard.get(0);
        final String key = voiceReplyKey;
        new AlertDialog.Builder(this)
                .setTitle("Send this reply?")
                .setMessage(text)
                .setPositiveButton("Send", (dialog, which) -> sendReply(key, text))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void sendReply(String key, String text) {
        JSONObject o = Link.msg("reply");
        Link.put(o, "key", key);
        Link.put(o, "text", text);
        if (!LinkService.send(o)) toast("Your phone is not connected");
    }
}
