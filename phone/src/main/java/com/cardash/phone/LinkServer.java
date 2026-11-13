package com.cardash.phone;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;

import com.cardash.link.Link;
import com.cardash.link.Proto;

import org.json.JSONObject;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;

/**
 * Waits for the car display to connect, over Bluetooth and (if switched on) over Wi-Fi.
 * Only one display is served at a time; a new connection replaces the old one.
 */
final class LinkServer {

    interface Events {
        void onLinkReady(String transport);

        void onLinkGone();

        void onCommand(JSONObject msg);
    }

    private final Context app;
    private final Handler main;
    private final Events events;
    private final SecureRandom random = new SecureRandom();

    private volatile boolean running;
    private volatile boolean wifiWanted;
    private volatile String code = "";
    private volatile Link active;
    private volatile BluetoothServerSocket btServer;
    private volatile ServerSocket tcpServer;
    private volatile String btStatus = "Starting";
    private Thread btThread;
    private Thread tcpThread;
    private int authFailures;
    private volatile long authBlockedUntil;

    LinkServer(Context app, Handler main, Events events) {
        this.app = app;
        this.main = main;
        this.events = events;
    }

    synchronized void start(boolean wifi, String pairingCode) {
        code = pairingCode;
        if (!running) {
            running = true;
            btThread = new Thread(this::btLoop, "cardash-bt-accept");
            btThread.start();
        }
        setWifi(wifi, pairingCode);
    }

    synchronized void setWifi(boolean on, String pairingCode) {
        code = pairingCode;
        wifiWanted = on;
        if (on) {
            if (tcpThread == null || !tcpThread.isAlive()) {
                tcpThread = new Thread(this::tcpLoop, "cardash-tcp-accept");
                tcpThread.start();
            }
        } else {
            closeQuietly(tcpServer);
            Link l = active;
            if (l != null && "wifi".equals(l.transport)) l.close("Wi-Fi backup switched off");
        }
    }

    boolean isConnected() {
        Link l = active;
        return l != null && !l.isClosed();
    }

    String transport() {
        Link l = active;
        return l == null ? "" : l.transport;
    }

    String bluetoothStatus() {
        return btStatus;
    }

    void send(JSONObject o) {
        Link l = active;
        if (l != null) l.send(o);
    }

    // ---- Bluetooth ----------------------------------------------------------------------------

    private boolean hasBtPermission() {
        return Build.VERSION.SDK_INT < 31
                || app.checkSelfPermission("android.permission.BLUETOOTH_CONNECT") == PackageManager.PERMISSION_GRANTED;
    }

    private void btLoop() {
        while (running) {
            try {
                BluetoothManager bm = (BluetoothManager) app.getSystemService(Context.BLUETOOTH_SERVICE);
                BluetoothAdapter a = bm == null ? null : bm.getAdapter();
                if (a == null) {
                    btStatus = "This phone has no Bluetooth";
                    nap(15000);
                    continue;
                }
                if (!hasBtPermission()) {
                    btStatus = "Bluetooth permission not granted";
                    nap(2000);
                    continue;
                }
                if (!a.isEnabled()) {
                    btStatus = "Bluetooth is off";
                    nap(3000);
                    continue;
                }
                BluetoothServerSocket ss = a.listenUsingRfcommWithServiceRecord(Proto.SDP_NAME, Proto.SPP_UUID);
                btServer = ss;
                btStatus = "Ready";
                try {
                    while (running) {
                        BluetoothSocket s = ss.accept();
                        adopt(s.getInputStream(), s.getOutputStream(), s, "bluetooth", false);
                    }
                } finally {
                    btServer = null;
                    closeQuietly(ss);
                }
            } catch (SecurityException e) {
                btStatus = "Bluetooth permission not granted";
                nap(2000);
            } catch (IOException e) {
                btStatus = "Bluetooth is restarting";
                nap(3000);
            }
        }
    }

    // ---- Wi-Fi --------------------------------------------------------------------------------

    private void tcpLoop() {
        while (running && wifiWanted) {
            ServerSocket ss = null;
            try {
                ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(Proto.TCP_PORT));
                tcpServer = ss;
                while (running && wifiWanted) {
                    Socket s = ss.accept();
                    if (SystemClock.elapsedRealtime() < authBlockedUntil) {
                        closeQuietly(s);
                        continue;
                    }
                    s.setTcpNoDelay(true);
                    adopt(s.getInputStream(), s.getOutputStream(), s, "wifi", true);
                }
            } catch (IOException e) {
                if (running && wifiWanted) nap(3000);
            } finally {
                tcpServer = null;
                closeQuietly(ss);
            }
        }
    }

    // ---- Handshake ----------------------------------------------------------------------------

    private final class Peer implements Link.Listener {
        final boolean needsAuth;
        final String nonce;
        Link link;
        volatile boolean ready;

        Peer(boolean needsAuth) {
            this.needsAuth = needsAuth;
            byte[] raw = new byte[16];
            random.nextBytes(raw);
            StringBuilder sb = new StringBuilder();
            for (byte b : raw) sb.append(String.format("%02x", b));
            nonce = sb.toString();
        }

        @Override
        public void onMessage(Link l, JSONObject m) {
            String t = m.optString("t");
            if (!ready) {
                if (!"hello".equals(t)) return;
                if (needsAuth && !Proto.sameText(Proto.hmacHex(code, nonce), m.optString("mac"))) {
                    noteAuthFailure();
                    l.send(Link.put(Link.msg("bye"), "why", "auth"));
                    main.postDelayed(() -> l.close("wrong pairing code"), 500);
                    return;
                }
                ready = true;
                main.post(() -> promote(this));
                return;
            }
            main.post(() -> {
                if (active == l) events.onCommand(m);
            });
        }

        @Override
        public void onClosed(Link l, String reason) {
            main.post(() -> {
                if (active == l) {
                    active = null;
                    events.onLinkGone();
                }
            });
        }
    }

    private void adopt(InputStream in, OutputStream out, Closeable closer, String transport, boolean needsAuth) {
        // The phone may have been asleep; keep it awake long enough to finish saying hello.
        try {
            PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
            if (pm != null) pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cardash:hello").acquire(15000);
        } catch (Exception ignored) {
        }
        Peer p = new Peer(needsAuth);
        Link l = new Link(in, out, closer, transport);
        p.link = l;
        l.start(p);
        JSONObject hello = Link.msg("hello");
        Link.put(hello, "v", Proto.VERSION);
        Link.put(hello, "name", Build.MODEL == null ? "Phone" : Build.MODEL);
        Link.put(hello, "auth", needsAuth);
        Link.put(hello, "nonce", p.nonce);
        l.send(hello);
        main.postDelayed(() -> {
            if (!p.ready) l.close("the display did not finish connecting");
        }, 10000);
    }

    /** Main thread: this display finished the handshake and becomes the one we talk to. */
    private void promote(Peer p) {
        if (p.link.isClosed()) return;
        Link old = active;
        active = p.link;
        if (old != null && old != p.link) old.close("replaced by a new connection");
        authFailures = 0;
        p.link.send(Link.put(Link.msg("ready"), "now", System.currentTimeMillis()));
        events.onLinkReady(p.link.transport);
    }

    private synchronized void noteAuthFailure() {
        authFailures++;
        if (authFailures >= 5) {
            authFailures = 0;
            authBlockedUntil = SystemClock.elapsedRealtime() + 60000;
        }
    }

    private static void closeQuietly(Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Exception ignored) {
        }
    }

    private static void nap(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
