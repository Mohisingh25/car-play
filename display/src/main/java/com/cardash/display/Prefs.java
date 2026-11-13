package com.cardash.display;

import android.content.Context;
import android.content.SharedPreferences;

/** Saved settings of the display app. */
final class Prefs {
    private Prefs() {}

    static final String AUTO_OPEN = "auto_open";
    static final String BANNER = "banner";
    static final String BT_ADDR = "bt_addr";
    static final String BT_NAME = "bt_name";
    static final String BT_LAST = "bt_last";
    static final String WIFI_ON = "wifi_on";
    static final String WIFI_CODE = "wifi_code";
    static final String WIFI_HOST = "wifi_host";

    static SharedPreferences of(Context c) {
        return c.getSharedPreferences("cardash", Context.MODE_PRIVATE);
    }

    static boolean flag(Context c, String key, boolean fallback) {
        return of(c).getBoolean(key, fallback);
    }

    static String text(Context c, String key) {
        String v = of(c).getString(key, "");
        return v == null ? "" : v;
    }

    static void set(Context c, String key, boolean value) {
        of(c).edit().putBoolean(key, value).apply();
    }

    static void set(Context c, String key, String value) {
        of(c).edit().putString(key, value).apply();
    }
}
