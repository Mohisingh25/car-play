package com.cardash.display;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;

import com.cardash.link.Link;
import com.cardash.link.Proto;

import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Keeps trying to reach the phone app: first over Bluetooth, then (if switched on) over the
 * phone's Wi-Fi hotspot. Reconnects by itself whenever the link drops.
 */
final class LinkClient implements Link.Listener {

    interface Events {
        /** Main thread. */
        void onStatus(boolean connected, String transport, String phoneName, String hint);

        /** Main thread. */
        void onMessage(JSONObject m);
    }

    private final Context app;
    private final Handler main;
    private final Events events;
    private final Object gate = new Object();

    private volatile boolean running;
    private volatile Link link;
    private volatile boolean ready;
    private volatile boolean sessionOver;
    private volatile String peerName = "";
    private Thread thread;
    private String btNote = "";
    private String wifiNote = "";
    private String btCandidate = "";

    LinkClient(Context app, Handler main, Events events) {
        this.app = app;
        this.main = main;
        this.events = events;
    }

    synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::loop, "cardash-connect");
        thread.start();
    }

    synchronized void stop() {
        running = false;
        Link l = link;
        if (l != null) l.close("stopped");
        if (thread != null) thread.interrupt();
        wake();
    }

    /** Try again right away, for example after a setting or permission changed. */
    void kick() {
        Thread t = thread;
        if (t != null && !ready) t.interrupt();
    }

    boolean send(JSONObject o) {
        Link l = link;
        return l != null && ready && l.send(o);
    }

    // ---- Connection loop ------------------------------------------------------------------------

    private void loop() {
        long wait = 1500;
        while (running) {
            Link l = null;
            BluetoothSocket bs = null;
            try {
                bs = connectBluetooth();
                if (bs != null) l = new Link(bs.getInputStream(), bs.getOutputStream(), bs, "bluetooth");
            } catch (Exception e) {
                btNote = "Bluetooth problem: " + e.getMessage();
                closeQuietly(bs);
            }
            if (l == null && running && Prefs.flag(app, Prefs.WIFI_ON, false)) {
                Socket s = null;
                try {
                    s = connectWifi();
                    if (s != null) l = new Link(s.getInputStream(), s.getOutputStream(), s, "wifi");
                } catch (Exception e) {
                    wifiNote = "Wi-Fi problem: " + e.getMessage();
                    closeQuietly(s);
                }
            }
            if (!running) {
                if (l != null) l.close("stopped");
                break;
            }
            if (l == null) {
                status(false, "", hint());
                pause(wait);
                wait = Math.min(wait * 2, 15000);
                continue;
            }
            wait = 1500;
            runSession(l);
            if (running) {
                status(false, "", "Reconnecting to your phone…");
                pause(1000);
            }
        }
    }

    private void runSession(Link l) {
        ready = false;
        sessionOver = false;
        link = l;
        long startedAt = SystemClock.elapsedRealtime();
        l.start(this);
        synchronized (gate) {
            while (!sessionOver && running) {
                if (!ready && SystemClock.elapsedRealtime() - startedAt > 12000) {
                    l.close("the phone app did not answer");
                    break;
                }
                try {
                    gate.wait(1000);
                } catch (InterruptedException ignored) {
                }
            }
        }
        l.close("done");
        ready = false;
        link = null;
    }

    private void wake() {
        synchronized (gate) {
            gate.notifyAll();
        }
    }

    @Override
    public void onMessage(Link l, JSONObject m) {
        String t = m.optString("t");
        if ("hello".equals(t)) {
            peerName = m.optString("name");
            JSONObject hello = Link.msg("hello");
            Link.put(hello, "v", Proto.VERSION);
            if (m.optBoolean("auth")) {
                Link.put(hello, "mac", Proto.hmacHex(Prefs.text(app, Prefs.WIFI_CODE), m.optString("nonce")));
            }
            l.send(hello);
            return;
        }
        if ("bye".equals(t)) {
            if ("auth".equals(m.optString("why"))) {
                wifiNote = "The pairing code does not match the one shown in the phone app";
            }
            return;
        }
        if ("ready".equals(t)) {
            ready = true;
            if ("bluetooth".equals(l.transport) && !btCandidate.isEmpty()) Prefs.set(app, Prefs.BT_LAST, btCandidate);
            final long offset = m.optLong("now", System.currentTimeMillis()) - System.currentTimeMillis();
            final String via = l.transport;
            final String name = peerName;
            main.post(() -> {
                DashState.I.clockOffset = offset;
                events.onStatus(true, via, name, "");
            });
            return;
        }
        if (!ready) return;
        main.post(() -> events.onMessage(m));
    }

    @Override
    public void onClosed(Link l, String reason) {
        sessionOver = true;
        wake();
    }

    private void status(boolean connected, String transport, String hint) {
        main.post(() -> events.onStatus(connected, transport, "", hint));
    }

    private String hint() {
        if (Prefs.flag(app, Prefs.WIFI_ON, false) && !wifiNote.isEmpty()) {
            return btNote + "\nWi-Fi backup: " + wifiNote;
        }
        return btNote;
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Exception ignored) {
        }
    }

    private void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    // ---- Bluetooth ------------------------------------------------------------------------------

    static boolean hasBtPermission(Context c) {
        return Build.VERSION.SDK_INT < 31
                || c.checkSelfPermission("android.permission.BLUETOOTH_CONNECT") == PackageManager.PERMISSION_GRANTED;
    }

    static BluetoothAdapter adapter(Context c) {
        BluetoothManager bm = (BluetoothManager) c.getSystemService(Context.BLUETOOTH_SERVICE);
        return bm == null ? null : bm.getAdapter();
    }

    private BluetoothSocket connectBluetooth() {
        BluetoothAdapter a = adapter(app);
        if (a == null) {
            btNote = "This display's Bluetooth cannot be used by apps. Use the Wi-Fi backup in Settings.";
            return null;
        }
        if (!hasBtPermission(app)) {
            btNote = "Allow the \"Nearby devices\" permission for Car Dash.";
            return null;
        }
        if (!a.isEnabled()) {
            btNote = "Bluetooth is off on this display.";
            return null;
        }
        Set<BluetoothDevice> bonded = a.getBondedDevices();
        if (bonded == null || bonded.isEmpty()) {
            btNote = "No paired phone found. Pair your phone in this display's Bluetooth settings. "
                    + "If it is already paired, use the Wi-Fi backup in Settings.";
            return null;
        }

        String chosen = Prefs.text(app, Prefs.BT_ADDR);
        String last = Prefs.text(app, Prefs.BT_LAST);
        List<BluetoothDevice> order = new ArrayList<>();
        if (!chosen.isEmpty()) {
            for (BluetoothDevice d : bonded) if (chosen.equals(d.getAddress())) order.add(d);
        } else {
            for (BluetoothDevice d : bonded) if (last.equals(d.getAddress())) order.add(d);
            for (BluetoothDevice d : bonded) if (!order.contains(d) && looksLikePhone(d)) order.add(d);
        }
        if (order.isEmpty()) {
            btNote = "The chosen phone is not paired with this display any more. Pick it again in Settings.";
            return null;
        }

        for (BluetoothDevice d : order) {
            if (!running) return null;
            BluetoothSocket s = null;
            try {
                s = d.createRfcommSocketToServiceRecord(Proto.SPP_UUID);
                s.connect();
                btCandidate = d.getAddress();
                return s;
            } catch (Exception e) {
                if (s != null) {
                    try {
                        s.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        btNote = "Phone not reachable over Bluetooth. Check that Bluetooth is on and that the "
                + "Car Dash Companion app on the phone is set up.";
        return null;
    }

    private static boolean looksLikePhone(BluetoothDevice d) {
        BluetoothClass c = d.getBluetoothClass();
        if (c == null) return true;
        int major = c.getMajorDeviceClass();
        return major == BluetoothClass.Device.Major.PHONE || major == BluetoothClass.Device.Major.UNCATEGORIZED;
    }

    // ---- Wi-Fi backup ---------------------------------------------------------------------------

    private Socket connectWifi() {
        if (Prefs.text(app, Prefs.WIFI_CODE).length() != 6) {
            wifiNote = "Enter the 6-digit code from the phone app in Settings.";
            return null;
        }
        String manual = Prefs.text(app, Prefs.WIFI_HOST).trim();
        if (!manual.isEmpty()) {
            Socket s = open(null, manual);
            if (s == null) wifiNote = "No answer from " + manual + ".";
            return s;
        }
        ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return null;
        boolean onWifi = false;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            if (nc == null || !nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
            LinkProperties lp = cm.getLinkProperties(n);
            if (lp == null) continue;
            for (RouteInfo r : lp.getRoutes()) {
                InetAddress g = r.getGateway();
                if (!r.isDefaultRoute() || !(g instanceof Inet4Address) || g.isAnyLocalAddress()) continue;
                onWifi = true;
                Socket s = open(n, g.getHostAddress());
                if (s != null) return s;
            }
        }
        wifiNote = onWifi
                ? "The phone is not answering. Turn on \"Wi-Fi backup\" in the phone app."
                : "Connect this display to your phone's Wi-Fi hotspot.";
        return null;
    }

    private Socket open(Network n, String host) {
        Socket s = new Socket();
        try {
            if (n != null) n.bindSocket(s);
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress(host, Proto.TCP_PORT), 2500);
            return s;
        } catch (Exception e) {
            try {
                s.close();
            } catch (Exception ignored) {
            }
            return null;
        }
    }
}
