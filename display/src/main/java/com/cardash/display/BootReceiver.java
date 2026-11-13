package com.cardash.display;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts Car Dash when the display boots (or after the app is updated). */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        boolean booted = !Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction());
        try {
            Intent i = new Intent(context, LinkService.class);
            if (booted) i.putExtra(LinkService.EXTRA_OPEN, true);
            context.startForegroundService(i);
        } catch (Exception ignored) {
            // Some displays block background starts; the app still works once opened by hand.
        }
    }
}
