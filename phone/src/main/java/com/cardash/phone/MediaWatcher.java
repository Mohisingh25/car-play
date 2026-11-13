package com.cardash.phone;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.SystemClock;
import android.view.KeyEvent;

import com.cardash.link.Link;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Follows whatever is playing on the phone (Spotify, YouTube Music, etc.) and controls it. */
final class MediaWatcher {

    interface Events {
        void onMedia(JSONObject state, Bitmap art, String artId);
    }

    private final Context app;
    private final Handler main;
    private final Events events;
    private final MediaSessionManager msm;
    private final ComponentName listenerName;
    private final List<MediaController> tracked = new ArrayList<>();
    private boolean started;

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = this::track;

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            publishSoon();
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            publishSoon();
        }

        @Override
        public void onSessionDestroyed() {
            refresh();
        }
    };

    private final Runnable publish = this::publishNow;

    MediaWatcher(Context app, Handler main, Events events) {
        this.app = app;
        this.main = main;
        this.events = events;
        this.msm = (MediaSessionManager) app.getSystemService(Context.MEDIA_SESSION_SERVICE);
        this.listenerName = new ComponentName(app, PhoneListenerService.class);
    }

    /** Needs notification access; safe to call again and again. */
    void start() {
        if (msm == null) return;
        try {
            if (!started) {
                msm.addOnActiveSessionsChangedListener(sessionsChanged, listenerName, main);
                started = true;
            }
            refresh();
        } catch (SecurityException e) {
            started = false;
        }
    }

    void stop() {
        if (msm != null && started) {
            try {
                msm.removeOnActiveSessionsChangedListener(sessionsChanged);
            } catch (Exception ignored) {
            }
        }
        started = false;
        track(null);
    }

    private void refresh() {
        if (msm == null) return;
        try {
            track(msm.getActiveSessions(listenerName));
        } catch (SecurityException e) {
            track(null);
        }
    }

    private void track(List<MediaController> list) {
        for (MediaController c : tracked) {
            try {
                c.unregisterCallback(callback);
            } catch (Exception ignored) {
            }
        }
        tracked.clear();
        if (list != null) {
            for (MediaController c : list) {
                try {
                    c.registerCallback(callback, main);
                    tracked.add(c);
                } catch (Exception ignored) {
                }
            }
        }
        publishSoon();
    }

    /** The player that is playing right now, otherwise the most recent one. */
    private MediaController best() {
        MediaController first = null;
        for (MediaController c : tracked) {
            if (first == null) first = c;
            PlaybackState ps = c.getPlaybackState();
            if (ps != null && ps.getState() == PlaybackState.STATE_PLAYING) return c;
        }
        return first;
    }

    private void publishSoon() {
        main.removeCallbacks(publish);
        main.postDelayed(publish, 150);
    }

    void publishNow() {
        main.removeCallbacks(publish);
        MediaController c = best();
        JSONObject o = Link.msg("media");
        Bitmap art = null;
        String artId = "";
        if (c == null) {
            Link.put(o, "state", "none");
        } else {
            MediaMetadata md = c.getMetadata();
            PlaybackState ps = c.getPlaybackState();
            String title = pick(md, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            String artist = pick(md, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
                    MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
            String album = pick(md, MediaMetadata.METADATA_KEY_ALBUM);
            long dur = md == null ? 0 : Math.max(0, md.getLong(MediaMetadata.METADATA_KEY_DURATION));
            int st = ps == null ? PlaybackState.STATE_NONE : ps.getState();
            String state;
            if (st == PlaybackState.STATE_PLAYING) state = "playing";
            else if (st == PlaybackState.STATE_BUFFERING || st == PlaybackState.STATE_CONNECTING) state = "buffering";
            else if (st == PlaybackState.STATE_NONE || st == PlaybackState.STATE_STOPPED
                    || st == PlaybackState.STATE_ERROR) state = "stopped";
            else state = "paused";
            long pos = 0;
            if (ps != null) {
                pos = Math.max(0, ps.getPosition());
                if (st == PlaybackState.STATE_PLAYING && ps.getLastPositionUpdateTime() > 0) {
                    long since = SystemClock.elapsedRealtime() - ps.getLastPositionUpdateTime();
                    pos += (long) (since * ps.getPlaybackSpeed());
                }
            }
            if (md != null) {
                art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
                if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            }
            if (art != null) {
                artId = Integer.toHexString((title + "|" + artist + "|" + album).hashCode())
                        + "-" + art.getWidth() + "x" + art.getHeight();
            }
            Link.put(o, "state", state);
            Link.put(o, "title", title);
            Link.put(o, "artist", artist);
            Link.put(o, "album", album);
            Link.put(o, "app", appLabel(c.getPackageName()));
            Link.put(o, "dur", dur);
            Link.put(o, "pos", pos);
        }
        Link.put(o, "artId", artId);
        events.onMedia(o, art, artId);
    }

    void command(String action) {
        MediaController c = best();
        if (c == null) {
            int code = "next".equals(action) ? KeyEvent.KEYCODE_MEDIA_NEXT
                    : "prev".equals(action) ? KeyEvent.KEYCODE_MEDIA_PREVIOUS
                    : KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
            AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
                am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
            }
            return;
        }
        MediaController.TransportControls tc = c.getTransportControls();
        PlaybackState ps = c.getPlaybackState();
        boolean playing = ps != null && ps.getState() == PlaybackState.STATE_PLAYING;
        if ("next".equals(action)) tc.skipToNext();
        else if ("prev".equals(action)) tc.skipToPrevious();
        else if ("play".equals(action)) tc.play();
        else if ("pause".equals(action)) tc.pause();
        else if (playing) tc.pause();
        else tc.play();
    }

    private static String pick(MediaMetadata md, String... keys) {
        if (md == null) return "";
        for (String k : keys) {
            CharSequence v = md.getText(k);
            if (v != null && v.length() > 0) return v.toString();
        }
        return "";
    }

    private String appLabel(String pkg) {
        if (pkg == null) return "";
        try {
            PackageManager pm = app.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            return "";
        }
    }
}
