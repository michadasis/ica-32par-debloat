package com.cleanhome.tv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {

    // Focus zones
    private static final int TOPBAR = 0, DOCK = 1, GRID = 2;
    private int zone = DOCK;
    private int topIndex = 0, dockIndex = 0, gridIndex = 0;

    private FrameLayout root;
    private LinearLayout page, dockRow, pillRow, inputDropdown;
    private View glow;
    private TextView greetingTv, clockTv, dateTv, heroName;
    private HighlightView highlight;
    private RecyclerView grid;

    private final List<AppInfo> dockApps = new ArrayList<>();
    private final List<AppInfo> gridApps = new ArrayList<>();
    private final List<View> dockTiles = new ArrayList<>();
    private final List<View> pills = new ArrayList<>();   // Inputs, Network, Settings, Lang
    private int columns = 4;
    private float density;

    private boolean dropdownOpen = false;
    private int dropdownIndex = 0;
    private final List<int[]> inputItems = new ArrayList<>(); // {labelKeyId} placeholder

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("cleanhome", MODE_PRIVATE);
        L.greek = prefs.getBoolean("greek", true);
        density = getResources().getDisplayMetrics().density;
        setContentView(R.layout.activity_main);

        root = findViewById(R.id.root);
        page = findViewById(R.id.page);
        dockRow = findViewById(R.id.dockRow);
        pillRow = findViewById(R.id.pillRow);
        inputDropdown = findViewById(R.id.inputDropdown);
        glow = findViewById(R.id.glow);
        greetingTv = findViewById(R.id.greeting);
        clockTv = findViewById(R.id.clock);
        dateTv = findViewById(R.id.date);
        heroName = findViewById(R.id.heroName);
        highlight = findViewById(R.id.highlight);
        grid = findViewById(R.id.grid);

        columns = getResources().getInteger(R.integer.grid_columns);

        loadApps();
        buildDock();
        buildPills();

        grid.setLayoutManager(new GridLayoutManager(this, columns));
        grid.setAdapter(new GridAdapter(gridApps));
        grid.setFocusable(false);
        grid.setItemAnimator(null);

        updateClock();
        scheduleClock();

        // Initial focus once the first layout pass has really happened
        root.getViewTreeObserver().addOnGlobalLayoutListener(
                new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                root.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                setHeroToDock(dockIndex);
                focusCurrent(false);
            }
        });
    }

    // ---------------------------------------------------------------- app load

    private int dp(float v) { return Math.round(v * density); }

    private void loadApps() {
        PackageManager pm = getPackageManager();
        String self = getPackageName();
        List<String> hidden = new ArrayList<>();
        for (String s : getResources().getStringArray(R.array.hidden_packages)) hidden.add(s);

        Map<String, AppInfo> byPkg = new LinkedHashMap<>();
        int[] cats = {0, 1}; // 0 = leanback first (preferred), 1 = normal launcher
        for (int c : cats) {
            Intent probe = new Intent(Intent.ACTION_MAIN);
            probe.addCategory(c == 0 ? Intent.CATEGORY_LEANBACK_LAUNCHER : Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> ris = pm.queryIntentActivities(probe, 0);
            for (ResolveInfo ri : ris) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(self) || hidden.contains(pkg) || byPkg.containsKey(pkg)) continue;
                Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
                if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
                if (launch == null) continue;
                CharSequence label = ri.loadLabel(pm);
                AppInfo info = new AppInfo(pkg, label, launch);
                try { info.banner = ri.activityInfo.loadBanner(pm); } catch (Throwable ignored) {}
                if (info.banner == null) { try { info.banner = ri.activityInfo.applicationInfo.loadBanner(pm); } catch (Throwable ignored) {} }
                try { info.icon = ri.loadIcon(pm); } catch (Throwable ignored) {}
                byPkg.put(pkg, info);
            }
        }

        // Dock = configured packages in order (only those installed); grid = the rest.
        List<String> dockCfg = new ArrayList<>();
        for (String s : getResources().getStringArray(R.array.dock_packages)) dockCfg.add(s);
        for (String p : dockCfg) {
            AppInfo a = byPkg.get(p);
            if (a != null) { dockApps.add(a); byPkg.remove(p); }
        }
        gridApps.addAll(byPkg.values());
        Collections.sort(gridApps, (x, y) ->
                x.label.toString().compareToIgnoreCase(y.label.toString()));

        // Precompute accent colors for dock apps (for the ambient glow)
        int fallback = getColor(R.color.accent_default);
        for (AppInfo a : dockApps) a.accent = ColorUtil.dominant(a.art(), fallback);
    }

    // ---------------------------------------------------------------- dock

    private void buildDock() {
        dockRow.removeAllViews();
        dockTiles.clear();
        int tileW = dp(228), tileH = dp(128), gap = dp(14);
        for (int i = 0; i < dockApps.size(); i++) {
            AppInfo a = dockApps.get(i);
            FrameLayout tile = new FrameLayout(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tileW, tileH);
            if (i > 0) lp.leftMargin = gap;
            tile.setLayoutParams(lp);
            tile.setBackgroundResource(R.drawable.tile_bg);
            tile.setClipToOutline(true);

            ImageView art = new ImageView(this);
            art.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            if (a.hasBanner()) {
                art.setScaleType(ImageView.ScaleType.CENTER_CROP);
                art.setImageDrawable(a.banner);
            } else {
                art.setScaleType(ImageView.ScaleType.FIT_CENTER);
                int pad = dp(28);
                art.setPadding(pad, pad, pad, pad);
                art.setImageDrawable(a.icon);
            }
            tile.addView(art);
            dockRow.addView(tile);
            dockTiles.add(tile);
        }
    }

    private void setHeroToDock(int i) {
        if (i < 0 || i >= dockApps.size()) return;
        AppInfo a = dockApps.get(i);
        heroName.setText(a.label);
        setGlow(a.accent);
    }

    private void setGlow(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientRadius(dp(360));
        g.setGradientCenter(0.32f, 0.5f);
        g.setColors(new int[]{ (color & 0x00FFFFFF) | 0xFF000000, (color & 0x00FFFFFF) });
        glow.setBackground(g);
        glow.animate().alpha(0.45f).setDuration(260).start();
    }

    // ---------------------------------------------------------------- pills

    private void buildPills() {
        pillRow.removeAllViews();
        pills.clear();
        String[] keys = {"inputs", "network", "settings", "lang"};
        for (int i = 0; i < keys.length; i++) {
            LinearLayout pill = new LinearLayout(this);
            pill.setOrientation(LinearLayout.HORIZONTAL);
            pill.setGravity(Gravity.CENTER_VERTICAL);
            pill.setBackgroundResource(R.drawable.pill_bg);
            int ph = dp(14), pv = dp(9);
            pill.setPadding(ph, pv, ph, pv);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
            if (i > 0) lp.leftMargin = dp(12);
            lp.gravity = Gravity.CENTER_VERTICAL;
            pill.setLayoutParams(lp);

            TextView tv = new TextView(this);
            tv.setTextColor(0xFFFFFFFF);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            tv.setText(pillText(keys[i]));
            pill.addView(tv);
            pill.setTag(keys[i]);
            pillRow.addView(pill);
            pills.add(pill);
        }
    }

    private String pillText(String key) {
        if (key.equals("network")) return networkName();
        return L.t(key);
    }

    private void refreshPillTexts() {
        String[] keys = {"inputs", "network", "settings", "lang"};
        for (int i = 0; i < pills.size(); i++) {
            TextView tv = (TextView) ((LinearLayout) pills.get(i)).getChildAt(0);
            tv.setText(pillText(keys[i]));
        }
    }

    private String networkName() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            android.net.Network n = cm.getActiveNetwork();
            NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
            if (nc == null) return L.t("offline");
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return L.t("ethernet");
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                String ssid = wm != null && wm.getConnectionInfo() != null ? wm.getConnectionInfo().getSSID() : null;
                if (ssid != null) ssid = ssid.replace("\"", "");
                if (ssid != null && !ssid.isEmpty() && !ssid.contains("unknown")) return ssid;
                return L.t("wifi");
            }
            return L.t("ethernet");
        } catch (Throwable t) {
            return L.t("network");
        }
    }

    // ---------------------------------------------------------------- clock

    private void scheduleClock() {
        ui.postDelayed(new Runnable() {
            @Override public void run() { updateClock(); ui.postDelayed(this, 15000); }
        }, 15000);
    }

    private void updateClock() {
        Locale loc = L.greek ? new Locale("el") : Locale.ENGLISH;
        clockTv.setText(new SimpleDateFormat("HH:mm", loc).format(new Date()));
        String d = new SimpleDateFormat("EEEE, d MMMM", loc).format(new Date());
        dateTv.setText(cap(d));
        greetingTv.setText(L.greeting());
    }

    private String cap(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase(L.greek ? new Locale("el") : Locale.ENGLISH) + s.substring(1);
    }

    // ---------------------------------------------------------------- focus / nav

    private View focusedView() {
        switch (zone) {
            case TOPBAR: return topIndex < pills.size() ? pills.get(topIndex) : null;
            case DOCK:   return dockIndex < dockTiles.size() ? dockTiles.get(dockIndex) : null;
            case GRID:   RecyclerView.LayoutManager lm = grid.getLayoutManager();
                         return lm == null ? null : lm.findViewByPosition(gridIndex);
        }
        return null;
    }

    private float gridScrollTy() {
        // translate page up so the dock sits near the top and the grid shows below
        return -(page.findViewById(R.id.dockArea).getTop() - dp(24));
    }

    private void focusCurrent(boolean animate) {
        float targetTy = (zone == GRID) ? gridScrollTy() : 0f;
        if (animate) page.animate().translationY(targetTy).setDuration(240).start();
        else page.setTranslationY(targetTy);

        // reset transient visuals on all tiles
        for (View t : dockTiles) t.animate().scaleX(1f).scaleY(1f).setDuration(160).start();
        for (View p : pills) p.animate().scaleX(1f).scaleY(1f).setDuration(160).start();
        for (int i = 0; i < gridApps.size(); i++) {
            View gv = gridLm().findViewByPosition(i);
            if (gv != null) { gv.animate().scaleX(1f).scaleY(1f).setDuration(160).start();
                View lbl = gv.findViewById(R.id.tileLabel); if (lbl != null) lbl.animate().alpha(0f).setDuration(120).start(); }
        }

        View v = focusedView();
        if (v == null) { root.post(() -> placeHighlight(targetTy, true)); return; }

        float scale = (zone == TOPBAR) ? 1.06f : 1.10f;
        v.animate().scaleX(scale).scaleY(scale).setDuration(180).start();
        if (zone == GRID) {
            View lbl = v.findViewById(R.id.tileLabel);
            if (lbl != null) lbl.animate().alpha(1f).setDuration(160).start();
        }
        if (zone == DOCK) setHeroToDock(dockIndex);

        highlight.setCornerRadiusDp(zone == TOPBAR ? 22 : 18);
        placeHighlight(targetTy, animate);
    }

    private GridLayoutManager gridLm() { return (GridLayoutManager) grid.getLayoutManager(); }

    private void placeHighlight(float finalTy, boolean animate) {
        View v = focusedView();
        if (v == null) return;
        int[] rl = new int[2]; root.getLocationOnScreen(rl);
        int[] vl = new int[2]; v.getLocationOnScreen(vl);
        float dyAdjust = finalTy - page.getTranslationY(); // account for in-flight scroll
        float left = vl[0] - rl[0];
        float top = vl[1] - rl[1] + dyAdjust;
        float w = v.getWidth(), h = v.getHeight();
        float padX, padY;
        if (zone == TOPBAR) { padX = dp(2); padY = dp(2); }
        else { padX = dp(10); padY = dp(10); }
        RectF r = new RectF(left - padX, top - padY, left + w + padX, top + h + padY);
        highlight.setAlpha(1f);
        highlight.moveTo(r, animate);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e);
        int k = e.getKeyCode();

        if (dropdownOpen) return handleDropdownKey(k);

        switch (k) {
            case KeyEvent.KEYCODE_DPAD_LEFT:  return moveLeft();
            case KeyEvent.KEYCODE_DPAD_RIGHT: return moveRight();
            case KeyEvent.KEYCODE_DPAD_UP:    return moveUp();
            case KeyEvent.KEYCODE_DPAD_DOWN:  return moveDown();
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:   return activate();
            case KeyEvent.KEYCODE_BACK:       return onBack();
        }
        return super.dispatchKeyEvent(e);
    }

    private boolean moveLeft() {
        if (zone == TOPBAR) { if (topIndex > 0) { topIndex--; focusCurrent(true);} return true; }
        if (zone == DOCK)   { if (dockIndex > 0) { dockIndex--; focusCurrent(true);} return true; }
        if (zone == GRID)   { if (gridIndex % columns != 0) { gridIndex--; focusCurrent(true);} return true; }
        return true;
    }

    private boolean moveRight() {
        if (zone == TOPBAR) { if (topIndex < pills.size()-1) { topIndex++; focusCurrent(true);} return true; }
        if (zone == DOCK)   { if (dockIndex < dockTiles.size()-1) { dockIndex++; focusCurrent(true);} return true; }
        if (zone == GRID)   { if (gridIndex % columns != columns-1 && gridIndex < gridApps.size()-1) { gridIndex++; focusCurrent(true);} return true; }
        return true;
    }

    private boolean moveUp() {
        if (zone == TOPBAR) return true;                       // top edge
        if (zone == DOCK)   { zone = TOPBAR; focusCurrent(true); return true; }
        if (zone == GRID) {
            if (gridIndex >= columns) { gridIndex -= columns; focusCurrent(true); }
            else { zone = DOCK; focusCurrent(true); }           // top row -> dock (scrolls home)
            return true;
        }
        return true;
    }

    private boolean moveDown() {
        if (zone == TOPBAR) { zone = DOCK; focusCurrent(true); return true; }
        if (zone == DOCK)   {
            if (!gridApps.isEmpty()) { zone = GRID; if (gridIndex >= gridApps.size()) gridIndex = 0; focusCurrent(true); }
            return true;
        }
        if (zone == GRID) {
            if (gridIndex + columns < gridApps.size()) { gridIndex += columns; focusCurrent(true); }
            return true;
        }
        return true;
    }

    private boolean onBack() {
        if (zone != DOCK) { zone = DOCK; focusCurrent(true); return true; }
        return true; // already home
    }

    private boolean activate() {
        if (zone == DOCK)  { launch(dockApps.get(dockIndex)); return true; }
        if (zone == GRID)  { launch(gridApps.get(gridIndex)); return true; }
        if (zone == TOPBAR){
            String key = (String) pills.get(topIndex).getTag();
            switch (key) {
                case "inputs":   openInputs(); break;
                case "network":  openSettings(Settings.ACTION_WIFI_SETTINGS); break;
                case "settings": openSettings(Settings.ACTION_SETTINGS); break;
                case "lang":     toggleLanguage(); break;
            }
            return true;
        }
        return true;
    }

    private void launch(AppInfo a) {
        try {
            a.launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(a.launch);
        } catch (Throwable t) { /* app may be unavailable */ }
    }

    private void openSettings(String action) {
        try { startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (Throwable t) {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (Throwable ignored) {}
        }
    }

    private void toggleLanguage() {
        L.greek = !L.greek;
        prefs.edit().putBoolean("greek", L.greek).apply();
        refreshPillTexts();
        updateClock();
        // re-place highlight since pill widths changed
        root.post(() -> focusCurrent(false));
    }

    // ---------------------------------------------------------------- inputs

    private void openInputs() {
        // Open the TV's native source picker (HDMI/AV/TV) via CVTE globalui.
        // This is the only app-accessible path on this firmware; standard
        // TvInputManager exposes no inputs here.
        try {
            Intent i = new Intent("cvte.intent.action.SHOW_UI_WITHOUT_VALUE");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return;
        } catch (Throwable ignored) {}
        try {
            Intent i = new Intent();
            i.setClassName("com.cvte.tv.globalui", "com.cvte.tv.globalui.InputSourceActivity");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable ignored) {}
    }

    private boolean handleDropdownKey(int k) {
        // reserved for the custom glass dropdown (see README); native picker used for now
        if (k == KeyEvent.KEYCODE_BACK) { closeDropdown(); return true; }
        return true;
    }

    private void closeDropdown() {
        dropdownOpen = false;
        inputDropdown.setVisibility(View.GONE);
        focusCurrent(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // network / time may have changed while away
        refreshPillTexts();
        updateClock();
        root.post(() -> focusCurrent(false));
    }
}
