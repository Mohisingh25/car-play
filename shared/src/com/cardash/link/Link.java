package com.cardash.link;

import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One open connection between the phone and the display.
 * Messages are JSON objects, one per line. Each side pings when idle and
 * drops the connection if nothing has arrived for a while.
 */
public final class Link {

    public interface Listener {
        /** Called on the reader thread. */
        void onMessage(Link link, JSONObject msg);

        /** Called once, from whichever thread noticed the connection ended. */
        void onClosed(Link link, String reason);
    }

    private static final int MAX_LINE = 1024 * 1024;
    private static final long PING_EVERY_MS = 5000;
    private static final long DEAD_AFTER_MS = 20000;
    private static final JSONObject STOP = new JSONObject();

    /** "bluetooth" or "wifi". */
    public final String transport;

    private final InputStream in;
    private final OutputStream out;
    private final Closeable closer;
    private final LinkedBlockingQueue<JSONObject> queue = new LinkedBlockingQueue<>(256);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile Listener listener;
    private volatile long lastRx;

    public Link(InputStream in, OutputStream out, Closeable closer, String transport) {
        this.in = in;
        this.out = out;
        this.closer = closer;
        this.transport = transport;
    }

    public void start(Listener l) {
        listener = l;
        lastRx = SystemClock.elapsedRealtime();
        new Thread(this::readLoop, "cardash-rx").start();
        new Thread(this::writeLoop, "cardash-tx").start();
    }

    /** Queue a message. Returns false if the link is closed or the queue is full. */
    public boolean send(JSONObject o) {
        return !closed.get() && queue.offer(o);
    }

    public boolean isClosed() {
        return closed.get();
    }

    public void close(String reason) {
        if (!closed.compareAndSet(false, true)) return;
        queue.clear();
        queue.offer(STOP);
        try {
            closer.close();
        } catch (Exception ignored) {
        }
        Listener l = listener;
        if (l != null) l.onClosed(this, reason == null ? "" : reason);
    }

    public static JSONObject msg(String type) {
        JSONObject o = new JSONObject();
        put(o, "t", type);
        return o;
    }

    public static JSONObject put(JSONObject o, String key, Object value) {
        try {
            o.put(key, value);
        } catch (JSONException ignored) {
        }
        return o;
    }

    private void readLoop() {
        try {
            BufferedInputStream bin = new BufferedInputStream(in, 8192);
            ByteArrayOutputStream line = new ByteArrayOutputStream(1024);
            while (!closed.get()) {
                int b = bin.read();
                if (b < 0) break;
                if (b == '\n') {
                    if (line.size() > 0) {
                        handle(new String(line.toByteArray(), StandardCharsets.UTF_8));
                        line.reset();
                    }
                } else {
                    if (line.size() >= MAX_LINE) throw new IOException("message too long");
                    line.write(b);
                }
            }
            close("connection ended");
        } catch (Exception e) {
            close(String.valueOf(e.getMessage()));
        }
    }

    private void handle(String text) {
        lastRx = SystemClock.elapsedRealtime();
        JSONObject o;
        try {
            o = new JSONObject(text);
        } catch (JSONException e) {
            return;
        }
        String t = o.optString("t");
        if ("ping".equals(t)) {
            send(msg("pong"));
            return;
        }
        if ("pong".equals(t)) return;
        Listener l = listener;
        if (l != null) l.onMessage(this, o);
    }

    private void writeLoop() {
        try {
            while (!closed.get()) {
                JSONObject o = queue.poll(PING_EVERY_MS, TimeUnit.MILLISECONDS);
                if (closed.get() || o == STOP) break;
                if (SystemClock.elapsedRealtime() - lastRx > DEAD_AFTER_MS) {
                    close("no answer from the other side");
                    break;
                }
                if (o == null) o = msg("ping");
                out.write((o.toString() + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (Exception e) {
            close(String.valueOf(e.getMessage()));
        }
    }
}
