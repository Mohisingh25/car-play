package com.cardash.display;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.SystemClock;
import android.util.Base64;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Everything the dashboard shows. Only touched on the main thread. */
final class DashState {

    static final DashState I = new DashState();

    static final int LINK = 1;
    static final int MEDIA = 2;
    static final int NAV = 4;
    static final int NOTIFS = 8;
    static final int PHONE = 16;

    interface Watcher {
        void onChanged(int what);
    }

    static final class Notif {
        String key = "";
        String app = "";
        String title = "";
        String text = "";
        long when;
        boolean canReply;
    }

    // Link
    boolean connected;
    String transport = "";
    String phoneName = "";
    String hint = "Looking for your phone…";

    // Music
    String mState = "none";
    String mTitle = "";
    String mArtist = "";
    String mAlbum = "";
    String mApp = "";
    long mDur;
    long mPos;
    long mPosAt;
    String artId = "";
    Bitmap art;

    // Directions
    boolean navActive;
    String navTitle = "";
    String navText = "";
    String navSub = "";
    Bitmap navIcon;

    // Phone
    int battery = -1;
    boolean charging;
    /** Phone clock minus display clock, so message ages are right even if the display's clock is wrong. */
    long clockOffset;
    private boolean syncing;

    final List<Notif> notifs = new ArrayList<>();
    final CopyOnWriteArrayList<Watcher> watchers = new CopyOnWriteArrayList<>();

    private DashState() {}

    private void fire(int what) {
        for (Watcher w : watchers) w.onChanged(what);
    }

    void setLink(boolean isConnected, String via, String name, String hintText) {
        boolean dropped = connected && !isConnected;
        connected = isConnected;
        transport = via == null ? "" : via;
        if (name != null && !name.isEmpty()) phoneName = name;
        hint = hintText == null ? "" : hintText;
        if (dropped) {
            syncing = false;
            mState = "none";
            navActive = false;
            battery = -1;
            fire(MEDIA | NAV | PHONE);
        }
        fire(LINK);
    }

    /** Where the song is right now, counting forward from the last update while it plays. */
    long position() {
        long p = mPos;
        if ("playing".equals(mState)) p += SystemClock.elapsedRealtime() - mPosAt;
        if (mDur > 0 && p > mDur) p = mDur;
        return Math.max(0, p);
    }

    /** Applies one message from the phone. Returns the notification if a new or changed one arrived. */
    Notif apply(JSONObject m) {
        String t = m.optString("t");
        switch (t) {
            case "reset":
                syncing = true;
                notifs.clear();
                fire(NOTIFS);
                return null;
            case "synced":
                syncing = false;
                return null;
            case "media": {
                mState = m.optString("state", "none");
                mTitle = m.optString("title");
                mArtist = m.optString("artist");
                mAlbum = m.optString("album");
                mApp = m.optString("app");
                mDur = m.optLong("dur");
                mPos = m.optLong("pos");
                mPosAt = SystemClock.elapsedRealtime();
                String id = m.optString("artId");
                if (!id.equals(artId)) {
                    artId = id;
                    art = null;
                }
                fire(MEDIA);
                return null;
            }
            case "art": {
                Bitmap b = decode(m.optString("b64"));
                if (b != null && m.optString("id").equals(artId)) {
                    art = b;
                    fire(MEDIA);
                }
                return null;
            }
            case "nav": {
                navActive = m.optBoolean("active");
                navTitle = m.optString("title");
                navText = m.optString("text");
                navSub = m.optString("sub");
                String icon = m.optString("icon");
                navIcon = icon.isEmpty() ? null : decode(icon);
                fire(NAV);
                return null;
            }
            case "notif": {
                String key = m.optString("key");
                if (key.isEmpty()) return null;
                for (int i = notifs.size() - 1; i >= 0; i--) {
                    if (notifs.get(i).key.equals(key)) notifs.remove(i);
                }
                Notif n = new Notif();
                n.key = key;
                n.app = m.optString("app");
                n.title = m.optString("title");
                n.text = m.optString("text");
                n.when = m.optLong("when");
                n.canReply = m.optBoolean("canReply");
                notifs.add(n);
                Collections.sort(notifs, (a, b) -> Long.compare(b.when, a.when));
                while (notifs.size() > 40) notifs.remove(notifs.size() - 1);
                fire(NOTIFS);
                return syncing ? null : n;
            }
            case "notif_gone":
                remove(m.optString("key"));
                return null;
            case "phone":
                battery = m.optInt("battery", -1);
                charging = m.optBoolean("charging");
                fire(PHONE);
                return null;
            default:
                return null;
        }
    }

    void remove(String key) {
        boolean any = false;
        for (int i = notifs.size() - 1; i >= 0; i--) {
            if (notifs.get(i).key.equals(key)) {
                notifs.remove(i);
                any = true;
            }
        }
        if (any) fire(NOTIFS);
    }

    private static Bitmap decode(String b64) {
        if (b64 == null || b64.isEmpty()) return null;
        try {
            byte[] raw = Base64.decode(b64, Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(raw, 0, raw.length);
        } catch (Exception e) {
            return null;
        }
    }
}
