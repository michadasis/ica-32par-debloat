package com.cleanhome.dpadnav;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

/**
 * This TV's firmware drops adb-enabled accessibility services on reboot. With
 * WRITE_SECURE_SETTINGS granted (via adb), this re-adds our service to the enabled
 * list on boot so D-pad navigation survives restarts without manual re-enabling.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String SVC = "com.cleanhome.dpadnav/com.cleanhome.dpadnav.DpadService";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        try {
            ContentResolver cr = ctx.getContentResolver();
            String cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (cur == null) cur = "";
            if (!cur.contains("com.cleanhome.dpadnav")) {
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        cur.isEmpty() ? SVC : cur + ":" + SVC);
            }
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        } catch (Exception ignored) { }
    }
}
