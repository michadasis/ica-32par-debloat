package com.cleanhome.weather;

import android.app.Activity;
import android.content.ContentResolver;
import android.net.Uri;
import android.os.Bundle;

/** Launched once post-install to clear the 'stopped' state and warm the weather cache, then finishes. */
public class InitActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        // Touch our own provider to trigger onCreate() -> background fetch.
        new Thread(() -> {
            try {
                ContentResolver cr = getContentResolver();
                Uri u = Uri.parse("content://com.cvte.tv.weather.export.WeatherDataContentProvider/weather");
                cr.query(u, null, null, null, null).close();
            } catch (Throwable ignored) {}
        }).start();
        finish();
    }
}
