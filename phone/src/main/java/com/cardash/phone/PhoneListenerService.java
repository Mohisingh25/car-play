package com.cardash.phone;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Android starts this once "Notification access" is switched on for the app, and keeps it running.
 * It is what lets the app see notifications and control the music player.
 */
public class PhoneListenerService extends NotificationListenerService {

    @Override
    public void onListenerConnected() {
        Hub.get(this).listenerUp(this);
    }

    @Override
    public void onListenerDisconnected() {
        Hub.get(this).listenerDown(this);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        Hub.get(this).posted(sbn);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        Hub.get(this).removed(sbn);
    }

    @Override
    public void onDestroy() {
        Hub.get(this).listenerDown(this);
        super.onDestroy();
    }
}
