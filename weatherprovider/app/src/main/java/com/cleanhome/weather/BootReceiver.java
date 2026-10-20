package com.cleanhome.weather;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/** Warms the weather cache after each boot so the widget has data quickly. */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(final Context ctx, Intent intent) {
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                ContentResolver cr = app.getContentResolver();
                Uri u = Uri.parse("content://com.cvte.tv.weather.export.WeatherDataContentProvider/weather");
                cr.query(u, null, null, null, null).close();
            } catch (Throwable ignored) {}
        }).start();
    }
}
