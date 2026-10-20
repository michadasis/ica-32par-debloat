package com.cleanhome.tv;

import android.content.Intent;
import android.graphics.drawable.Drawable;

/** One launchable app shown in the dock or grid. */
public class AppInfo {
    public final String pkg;
    public final CharSequence label;
    public final Intent launch;
    public Drawable banner;   // 16:9 banner if the app provides one
    public Drawable icon;     // square icon fallback
    public int accent = 0;    // color sampled from banner/icon for the ambient glow

    public AppInfo(String pkg, CharSequence label, Intent launch) {
        this.pkg = pkg;
        this.label = label;
        this.launch = launch;
    }

    /** Prefer the wide banner; fall back to the icon. */
    public Drawable art() {
        return banner != null ? banner : icon;
    }

    public boolean hasBanner() {
        return banner != null;
    }
}
