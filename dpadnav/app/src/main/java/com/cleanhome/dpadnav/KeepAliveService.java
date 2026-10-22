package com.cleanhome.dpadnav;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * A tiny always-foreground service whose only job is to keep this app's process at
 * foreground priority, so the vendor RAM manager (HiRMS) on this low-RAM TV doesn't
 * force-stop the accessibility service during heavy memory use (e.g. Netflix playback).
 */
public class KeepAliveService extends Service {

    private static final String CH = "dpadnav_keepalive";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CH, "D-pad navigation",
                    NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Notification n = new Notification.Builder(this, CH)
                .setContentTitle("D-pad navigation active")
                .setContentText("Keeps Netflix remote navigation running")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true)
                .build();
        startForeground(1, n);
        return START_STICKY;   // restart if the system kills us
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
