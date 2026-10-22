package com.cleanhome.dpadnav;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the D-pad into focus navigation for the Netflix phone app.
 * Arrows move a highlight box between on-screen clickable items (nearest in that
 * direction); OK clicks the highlighted item (ACTION_CLICK). At a row/edge it
 * scrolls the nearest scrollable list (ACTION_SCROLL_*) and re-picks.
 */
public class DpadService extends AccessibilityService {

    private static final String TAG = "DpadNav";
    private static final String NETFLIX = "com.netflix.mediaclient";
    private static final int EDGE = 48;   // px: ignore targets clipped to this sliver at top/bottom

    private final Handler ui = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private HighlightView highlight;
    private boolean overlayAdded = false;
    private boolean netflixActive = false;
    private boolean inPlayer = false;   // video playing -> let Netflix's player own the remote
    private boolean playerControlMode = false;  // Up pressed in player -> manual control grid
    private boolean submenuMode = false;        // a player submenu is open -> navigate its nodes
    private int controlIdx = 0;
    // Fixed player-control positions (fraction of screen): {cx, cy, kind}
    // kind: 0=tap, 1=play/pause (media key), 2=progress bar. Left/Right scrub on kind 1 & 2.
    // Grid controls driven by Netflix's MEDIA SESSION (reliable; session action flags = 875
    // include REWIND, PLAY_PAUSE, FAST_FORWARD, SKIP_TO_NEXT). {cx, cy} + matching media keycode.
    private static final int START_CTRL = 2;   // enter the grid on Play/Pause
    private static final int BAR = -1;         // progress-bar sentinel: Left/Right seek, Up/Down navigate
    private static final float[][] CTRL = {
        {0.906f, 0.139f}, // 0 Skip Intro
        {0.344f, 0.500f}, // 1 Rewind -10
        {0.500f, 0.500f}, // 2 Play / Pause
        {0.656f, 0.500f}, // 3 Forward +10
        {0.500f, 0.864f}, // 4 Progress bar
        {0.150f, 0.940f}, // 5 Speed (bottom bar)
        {0.320f, 0.940f}, // 6 Lock
        {0.490f, 0.940f}, // 7 Episodes
        {0.680f, 0.940f}, // 8 Audio & subtitles
        {0.870f, 0.940f}, // 9 Next episode
    };
    private static final int[] CTRL_KEY = {   // 0 = click node; BAR = progress bar; else media key
        0,
        KeyEvent.KEYCODE_MEDIA_REWIND,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        BAR,
        0, 0, 0, 0, 0,
    };

    private final Rect current = new Rect();
    private boolean haveCurrent = false;

    @Override
    protected void onServiceConnected() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        highlight = new HighlightView(this);
        Log.d(TAG, "service connected");
        startKeepAlive();   // stay alive/bound (idle outside Netflix); stopping lets HiRMS kill us for good
        // Netflix may already be foreground when we (re)connect -> catch it live
        ui.postDelayed(() -> {
            if (!netflixActive && isNetflixNow()) {
                netflixActive = true;
                Log.d(TAG, "netflix already foreground at connect");
                showAndPick();
            }
        }, 500);
    }

    private boolean keepAliveRunning = false;
    /** Foreground keep-alive runs ONLY while Netflix is in front (protects us from HiRMS there). */
    private void startKeepAlive() {
        if (keepAliveRunning) return;
        try { startForegroundService(new Intent(this, KeepAliveService.class)); keepAliveRunning = true; }
        catch (Exception e) { Log.e(TAG, "keepalive start", e); }
    }
    private void stopKeepAlive() {
        if (!keepAliveRunning) return;
        try { stopService(new Intent(this, KeepAliveService.class)); } catch (Exception ignored) { }
        keepAliveRunning = false;
    }

    /** live check of the focused app's package (self-heals a missed window event) */
    private boolean isNetflixNow() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            return root != null && root.getPackageName() != null
                    && NETFLIX.contentEquals(root.getPackageName());
        } catch (Exception e) { return false; }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int t = event.getEventType();
        if (t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence pkg = event.getPackageName();
            if (pkg != null && getPackageName().contentEquals(pkg)) return; // our own overlay -> ignore
            cacheTime = 0;   // UI changed -> drop the node cache
            boolean nowNetflix = pkg != null && NETFLIX.contentEquals(pkg);
            CharSequence cls = event.getClassName();
            boolean nowPlayer = cls != null && cls.toString().toLowerCase().contains("player");
            if (nowNetflix) {
                startKeepAlive();   // ensure protection (idempotent)
                boolean wasPlayer = inPlayer;
                inPlayer = nowPlayer;
                if (nowPlayer) {
                    haveCurrent = false; playerControlMode = false; submenuMode = false; hideOverlay();
                    Log.d(TAG, "player open -> nav off");
                } else if (!netflixActive || wasPlayer) {
                    Log.d(TAG, "netflix active" + (wasPlayer ? " (back from player)" : ""));
                    ui.postDelayed(this::showAndPick, 400);
                }
                netflixActive = true;
            } else if (netflixActive) {
                netflixActive = false; inPlayer = false; playerControlMode = false; submenuMode = false;
                haveCurrent = false; hideOverlay();     // go idle, but stay alive (keep-alive on)
                Log.d(TAG, "netflix left -> " + pkg);
            }
        } else if (t == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && netflixActive) {
            cacheTime = 0;
            if (submenuMode) { ui.removeCallbacks(submenuCheck); ui.postDelayed(submenuCheck, 250); }
            else if (!inPlayer && overlayAdded) { ui.removeCallbacks(reconcile); ui.postDelayed(reconcile, 250); }
        }
    }

    @Override public void onInterrupt() { }

    /** dispatch a media transport key to Netflix's media session (reliable; no screen tap) */
    private void sendMediaKey(int code) {
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            long t = android.os.SystemClock.uptimeMillis();
            am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
            am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
            Log.d(TAG, "media key " + code);
        } catch (Exception e) { Log.e(TAG, "mediaKey", e); }
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (!netflixActive) {
            if (isNetflixNow()) netflixActive = true;
            else return false;
        }
        // A player submenu (audio/subtitles/episodes) open -> navigate its nodes like browse.
        if (submenuMode) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) { submenuMode = false; hideOverlay(); haveCurrent = false; }
                return false;   // let Netflix close the submenu
            }
            // fall through to the node-navigation code below
        } else {
            // Player = explicit activity flag OR Netflix showing no interactive nodes (video surface).
            boolean player = inPlayer || collectClickable().isEmpty();
            if (player) {
                if (!playerControlMode) { if (overlayAdded) hideOverlay(); haveCurrent = false; }
                return handlePlayerKey(event);
            }
        }
        if (!overlayAdded) addOverlay();
        int code = event.getKeyCode();
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: break;
            default: return false;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:    move(0, -1); break;
            case KeyEvent.KEYCODE_DPAD_DOWN:  move(0, 1);  break;
            case KeyEvent.KEYCODE_DPAD_LEFT:  move(-1, 0); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: move(1, 0);  break;
            default:                          clickCurrent(); break;
        }
        return true;
    }

    /** In the video player: OK=play/pause, Up=Skip Intro, Down=TEST gesture-tap; seek/Back native. */
    private boolean handlePlayerKey(KeyEvent event) {
        int c = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;

        if (playerControlMode) {
            if (!down) return true;
            ui.removeCallbacks(exitControls);
            ui.postDelayed(exitControls, 7000);     // auto-exit after 7s idle
            boolean onBar = CTRL_KEY[controlIdx] == BAR;   // progress bar: L/R seek, Up/Down navigate
            Log.d(TAG, "grid key=" + c + " idx=" + controlIdx + " onBar=" + onBar);
            switch (c) {
                case KeyEvent.KEYCODE_DPAD_UP:    moveControl(0, -1); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:  moveControl(0, 1);  return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (onBar) sendMediaKey(KeyEvent.KEYCODE_MEDIA_REWIND); else moveControl(-1, 0);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (onBar) sendMediaKey(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD); else moveControl(1, 0);
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER: if (!onBar) activateControl(); return true;
                case KeyEvent.KEYCODE_BACK: exitControlMode(); return true;   // Back -> only close the grid
                default: exitControlMode(); return false;   // other keys -> leave mode, pass native
            }
        }

        if (c == KeyEvent.KEYCODE_DPAD_UP) {            // Up -> open the manual control grid
            if (down) enterControlMode();
            return true;
        }
        if (c == KeyEvent.KEYCODE_DPAD_CENTER || c == KeyEvent.KEYCODE_ENTER
                || c == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (down) sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);   // OK -> play/pause (not exit)
            return true;
        }
        return false;   // Left/Right/Down/Back etc. -> native
    }

    private void enterControlMode() {
        playerControlMode = true;
        controlIdx = START_CTRL;                         // start on Play/Pause
        addOverlay();
        highlightControl();
        ui.removeCallbacks(exitControls);
        ui.postDelayed(exitControls, 7000);
        Log.d(TAG, "control mode ON");
    }

    private void exitControlMode() {
        playerControlMode = false;
        ui.removeCallbacks(exitControls);
        hideOverlay();
        Log.d(TAG, "control mode OFF");
    }
    private final Runnable exitControls = this::exitControlMode;

    private void moveControl(int dx, int dy) {
        int[] sz = screenSize();
        int cx = (int) (sz[0] * CTRL[controlIdx][0]), cy = (int) (sz[1] * CTRL[controlIdx][1]);
        int best = -1; long bestScore = Long.MAX_VALUE;
        for (int i = 0; i < CTRL.length; i++) {
            if (i == controlIdx) continue;
            int tx = (int) (sz[0] * CTRL[i][0]), ty = (int) (sz[1] * CTRL[i][1]);
            int along = (tx - cx) * dx + (ty - cy) * dy;
            if (along <= 0) continue;
            int perp = Math.abs((tx - cx) * dy) + Math.abs((ty - cy) * dx);
            long score = (long) along + (long) perp * 8L;   // keep moves on-axis (same row/column)
            if (score < bestScore) { bestScore = score; best = i; }
        }
        Log.d(TAG, "moveControl(" + dx + "," + dy + ") -> " + best);
        if (best >= 0) { controlIdx = best; highlightControl(); }
    }

    private void highlightControl() {
        highlight.clipOut.setEmpty();   // player grid: no nav-bar clipping
        int[] sz = screenSize();
        int cx = (int) (sz[0] * CTRL[controlIdx][0]), cy = (int) (sz[1] * CTRL[controlIdx][1]);
        if (CTRL_KEY[controlIdx] == BAR) {                     // progress bar -> wide highlight
            current.set((int) (sz[0] * 0.03f), cy - 24, (int) (sz[0] * 0.93f), cy + 24);
        } else {
            current.set(cx - 85, cy - 36, cx + 85, cy + 36);
        }
        haveCurrent = true;
        highlight.set(current);
    }

    private void activateControl() {
        int key = CTRL_KEY[controlIdx];
        if (key == BAR) return;                         // progress bar has no OK action
        if (key == 0) {
            clickControl();                             // click the node (skip / settings-bar button)
            // Speed(5), Episodes(7), Audio&Subtitles(8) open a submenu -> navigate its nodes
            if (controlIdx == 5 || controlIdx == 7 || controlIdx == 8) {
                playerControlMode = false;
                submenuMode = true;
                ui.removeCallbacks(exitControls);
                ui.postDelayed(() -> { if (netflixActive && submenuMode) { addOverlay(); showAndPick(); } }, 650);
            }
        } else {
            sendMediaKey(key);                          // rewind / play-pause / forward
        }
        Log.d(TAG, "activate control " + controlIdx);
    }

    /** Activate a non-media control by clicking the accessibility node under it (browse-style). */
    private void clickControl() {
        int[] sz = screenSize();
        int x = (int) (sz[0] * CTRL[controlIdx][0]), y = (int) (sz[1] * CTRL[controlIdx][1]);
        for (AccessibilityNodeInfo root : netflixRoots()) {
            AccessibilityNodeInfo hit = findClickableContaining(root, x, y);
            if (hit != null && hit.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.d(TAG, "click node ok"); return;
            }
        }
        tap(x, y); ui.postDelayed(() -> tap(x, y), 330);   // fallback: gesture tap
        Log.d(TAG, "click fallback tap");
    }

    // ---------------- navigation ----------------
    /** Find the bottom nav/tab bar (a strip of narrow clickable items pinned to the screen
     *  bottom) so the highlight ring can be clipped to appear behind it. */
    private void updateNavbarClip(java.util.List<Rect> rects) {
        int[] sz = screenSize();
        int navTop = Integer.MAX_VALUE; boolean found = false;
        for (Rect r : rects) {
            if (r.top >= sz[1] * 0.86f && r.bottom >= sz[1] - 6 && r.width() < sz[0] * 0.45f) {
                navTop = Math.min(navTop, r.top); found = true;
            }
        }
        // Full-width bottom strip from the nav bar's top: content rings are clipped here so
        // every tile's bottom goes behind the strip, centre or edge alike.
        if (found) highlight.clipOut.set(0, navTop, sz[0], sz[1]); else highlight.clipOut.setEmpty();
    }

    private void showAndPick() {
        if (!netflixActive) return;
        addOverlay();
        List<Rect> rects = collectClickable();
        Log.d(TAG, "showAndPick: " + rects.size() + " clickable");
        if (rects.isEmpty()) { highlight.clear(); return; }
        updateNavbarClip(rects);
        int[] sz = screenSize();
        Rect best = nearest(rects, sz[0] / 2, sz[1] / 2);
        if (best != null) { current.set(best); haveCurrent = true; highlight.set(current); }
    }

    /** the persistent bottom tab bar lives in the bottom band; treat it as its own zone */
    private boolean isNavbar(Rect r, int sh) { return !imeUp && r.top >= sh - 72; }

    private void move(int dx, int dy) {
        if (!overlayAdded) addOverlay();
        List<Rect> rects = collectClickable();
        if (rects.isEmpty()) { Log.d(TAG, "move: no nodes"); return; }
        if (!haveCurrent) { showAndPick(); return; }
        updateNavbarClip(rects);
        int sh = screenSize()[1];
        boolean onNav = isNavbar(current, sh);

        // While browsing content, the navbar is excluded so DOWN scrolls to rows
        // rather than getting trapped on the tab bar. On the navbar, LEFT/RIGHT move
        // between tabs; UP leaves the navbar back into content.
        boolean wantNavbar = onNav && dy == 0;
        Rect best = pickDirectional(rects, dx, dy, wantNavbar, sh);

        if (best != null) {
            current.set(best); haveCurrent = true; highlight.set(current);
            Log.d(TAG, "move -> " + best.toShortString() + (isNavbar(best, sh) ? " [nav]" : ""));
            return;
        }
        // nothing in view that way -> scroll the list; if it can't scroll and we're
        // going down from content, drop into the navbar as the last stop
        boolean scrolled = scrollInDirection(dx, dy);
        Log.d(TAG, "scroll(" + dx + "," + dy + ")=" + scrolled);
        if (scrolled) {
            ui.postDelayed(this::renearest, 400);
        } else if (dy > 0 && !onNav && current.centerY() > sh * 0.55f) {
            // genuinely at the bottom of loaded content -> drop into the tab bar
            Rect nb = nearestNavbar(rects, sh);
            if (nb != null) { current.set(nb); haveCurrent = true; highlight.set(current); Log.d(TAG, "-> navbar"); }
        } else if (dy != 0) {
            // rows below the billboard may still be loading -> retry the scroll shortly,
            // rather than jumping to the navbar from the top of the page
            ui.postDelayed(() -> {
                if (!netflixActive) return;
                if (scrollInDirection(dx, dy)) ui.postDelayed(this::renearest, 400);
            }, 350);
        }
    }

    private Rect pickDirectional(List<Rect> rects, int dx, int dy, boolean wantNavbar, int sh) {
        Rect best = null; long bestScore = Long.MAX_VALUE;
        int ccx = current.centerX(), ccy = current.centerY();
        for (Rect r : rects) {
            if (r.equals(current)) continue;
            boolean nav = isNavbar(r, sh);
            if (wantNavbar != nav) continue;                 // stay in / out of the navbar zone
            int along = (r.centerX() - ccx) * dx + (r.centerY() - ccy) * dy;
            if (along <= 0) continue;
            int perp = Math.abs((r.centerX() - ccx) * dy) + Math.abs((r.centerY() - ccy) * dx);
            // Browse: penalise off-axis hard (reach far icons). Keyboard: light, so the nearest
            // (staggered) row wins over an aligned-but-farther row.
            long score = (long) along + (long) perp * (imeUp ? 1L : 8L);
            if (score < bestScore) { bestScore = score; best = r; }
        }
        return best;
    }

    private Rect nearestNavbar(List<Rect> rects, int sh) {
        Rect best = null; long bestD = Long.MAX_VALUE;
        for (Rect r : rects) {
            if (!isNavbar(r, sh)) continue;
            long ex = r.centerX() - current.centerX(), d = ex * ex;
            if (d < bestD) { bestD = d; best = r; }
        }
        return best;
    }

    /** after a scroll / content change, keep the highlight on the nearest item in the SAME zone */
    private void renearest() {
        List<Rect> rects = collectClickable();
        if (rects.isEmpty()) return;
        int sh = screenSize()[1];
        boolean curNav = haveCurrent && isNavbar(current, sh);
        Rect best = null; long bestD = Long.MAX_VALUE;
        for (Rect r : rects) {
            if (isNavbar(r, sh) != curNav) continue;
            long ex = r.centerX() - current.centerX(), ey = r.centerY() - current.centerY(), d = ex * ex + ey * ey;
            if (d < bestD) { bestD = d; best = r; }
        }
        if (best == null) {
            for (Rect r : rects) {
                long ex = r.centerX() - current.centerX(), ey = r.centerY() - current.centerY(), d = ex * ex + ey * ey;
                if (d < bestD) { bestD = d; best = r; }
            }
        }
        if (best != null) { current.set(best); haveCurrent = true; highlight.set(current); }
    }
    private final Runnable reconcile = this::renearest;

    /** while a player submenu is open: drop out of submenu mode once it closes (nodes gone) */
    private final Runnable submenuCheck = () -> {
        if (!submenuMode) return;
        if (collectClickable().isEmpty()) { submenuMode = false; hideOverlay(); haveCurrent = false; }
        else if (!haveCurrent) showAndPick();
    };

    private Rect nearest(List<Rect> rects, int x, int y) {
        Rect best = null; long bestD = Long.MAX_VALUE;
        for (Rect r : rects) {
            long ex = r.centerX() - x, ey = r.centerY() - y, d = ex * ex + ey * ey;
            if (d < bestD) { bestD = d; best = r; }
        }
        return best;
    }

    // ---------------- actions ----------------
    private void clickCurrent() {
        if (!haveCurrent) return;
        if (imeUp && current.centerY() > screenSize()[1] * 0.60f) {   // keyboard key -> edit the field
            int[] sz = screenSize();
            int ci = -1; long bd = Long.MAX_VALUE;
            for (int i = 0; i < KEYS.length; i++) {
                long dx = (long) (sz[0] * KEYS[i][0]) - current.centerX();
                long dy = (long) (sz[1] * KEYS[i][1]) - current.centerY();
                long d = dx * dx + dy * dy;
                if (d < bd) { bd = d; ci = i; }
            }
            if (ci >= 0) typeKey(KEY_CHARS[ci]);
            return;
        }
        // Click the node that IS the highlighted box (exact bounds), not merely a
        // bigger clickable that happens to contain the point (e.g. the billboard).
        AccessibilityNodeInfo hit = null;
        for (AccessibilityNodeInfo root : netflixRoots()) {
            hit = findByBounds(root, current); if (hit != null) break;
        }
        if (hit == null) {
            for (AccessibilityNodeInfo root : netflixRoots()) {
                hit = findClickableContaining(root, current.centerX(), current.centerY());
                if (hit != null) break;
            }
        }
        AccessibilityNodeInfo clickable = hit;
        while (clickable != null && !clickable.isClickable()) clickable = clickable.getParent();
        if (clickable != null) {
            boolean ok = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            Log.d(TAG, "click ACTION_CLICK=" + ok + " on " + clickable.getClassName()
                    + " exact=" + (hit != null));
            if (ok) return;
        }
        tap(current.centerX(), current.centerY());
        Log.d(TAG, "click fallback tap");
    }

    /** Edit the focused search field directly (reliable; no keyboard tap needed). */
    private void typeKey(String ch) {
        if (ch == null || ch.equals("SKIP")) return;
        AccessibilityNodeInfo field = null;
        for (AccessibilityNodeInfo root : netflixRoots()) { field = findEditable(root); if (field != null) break; }
        if (field == null) { Log.d(TAG, "type: no field"); return; }
        CharSequence cur = field.getText();
        String s = cur == null ? "" : cur.toString();
        CharSequence hint = field.getHintText();          // empty fields report the placeholder
        if (hint != null && s.contentEquals(hint)) s = "";
        if (ch.equals("DEL")) { if (!s.isEmpty()) s = s.substring(0, s.length() - 1); }
        else s = s + ch;
        android.os.Bundle b = new android.os.Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, s);
        boolean ok = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
        Log.d(TAG, "type '" + ch + "' -> '" + s + "' ok=" + ok);
    }

    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo n) {
        if (n == null) return null;
        if (n.isEditable()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = findEditable(n.getChild(i));
            if (r != null) return r;
        }
        return null;
    }

    /** the visible clickable/checkable node whose bounds exactly equal the highlighted box */
    private AccessibilityNodeInfo findByBounds(AccessibilityNodeInfo node, Rect target) {
        if (node == null) return null;
        Rect b = new Rect(); node.getBoundsInScreen(b);
        if (b.equals(target) && node.isVisibleToUser()
                && (node.isClickable() || node.isCheckable())) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo r = findByBounds(node.getChild(i), target);
            if (r != null) return r;
        }
        return null;
    }

    /** smallest clickable node whose bounds contain (x,y) */
    private AccessibilityNodeInfo findClickableContaining(AccessibilityNodeInfo node, int x, int y) {
        if (node == null) return null;
        Rect b = new Rect(); node.getBoundsInScreen(b);
        if (!b.contains(x, y)) return null;
        AccessibilityNodeInfo found = null;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo c = node.getChild(i);
            AccessibilityNodeInfo r = findClickableContaining(c, x, y);
            if (r != null) { found = r; break; }
        }
        if (found != null) return found;
        if (node.isClickable() && node.isVisibleToUser()) return node;
        return null;
    }

    private boolean scrollInDirection(int dx, int dy) {
        boolean horizontal = dx != 0;
        List<AccessibilityNodeInfo> scs = new ArrayList<>();
        for (AccessibilityNodeInfo root : netflixRoots()) gatherScrollable(root, scs);
        if (scs.size() < 2) dumpWindows();
        int fwd = dx > 0 || dy > 0 ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                                   : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
        AccessibilityNodeInfo sc = chooseScrollable(scs, horizontal, fwd, current.centerX(), current.centerY());
        Log.d(TAG, "scrollables=" + scs.size());
        if (sc == null) { Log.d(TAG, "scroll: none, swipe"); swipe(dx, dy); return false; }
        boolean ok = sc.performAction(fwd);
        Rect sb = new Rect(); sc.getBoundsInScreen(sb);
        Log.d(TAG, "scroll " + sc.getClassName() + " " + sb.toShortString() + " ok=" + ok);
        if (!ok) swipe(dx, dy);
        return ok;
    }

    /** scrollable if the flag is set OR it advertises a scroll action in the wanted direction */
    private boolean canScroll(AccessibilityNodeInfo n, int wantAction) {
        if (n.isScrollable()) return true;
        try {
            for (AccessibilityNodeInfo.AccessibilityAction a : n.getActionList()) {
                int id = a.getId();
                if (id == wantAction || id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                        || id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    private void gatherScrollable(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        if (node.isScrollable()) out.add(node);
        else {
            try {
                for (AccessibilityNodeInfo.AccessibilityAction a : node.getActionList()) {
                    int id = a.getId();
                    if (id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                            || id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) { out.add(node); break; }
                }
            } catch (Exception ignored) { }
        }
        for (int i = 0; i < node.getChildCount(); i++) gatherScrollable(node.getChild(i), out);
    }

    /**
     * Prefer scrollables that contain the cursor; among those, for horizontal movement
     * pick the shortest (one row), for vertical pick the tallest (the page grid).
     */
    private AccessibilityNodeInfo chooseScrollable(List<AccessibilityNodeInfo> list, boolean horizontal,
                                                   int wantAction, int x, int y) {
        AccessibilityNodeInfo best = null; int bestH = horizontal ? Integer.MAX_VALUE : -1; boolean bestIn = false;
        for (AccessibilityNodeInfo n : list) {
            Rect b = new Rect(); n.getBoundsInScreen(b);
            boolean in = b.contains(x, y);
            int h = b.height();
            if (best == null || (in && !bestIn)) { best = n; bestH = h; bestIn = in; continue; }
            if (in == bestIn && (horizontal ? h < bestH : h > bestH)) { best = n; bestH = h; }
        }
        return best;
    }

    // ---------------- node collection ----------------
    /** Netflix lays the home screen across several windows; gather the roots of all of them */
    private List<AccessibilityNodeInfo> netflixRoots() {
        List<AccessibilityNodeInfo> list = new ArrayList<>();
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r != null && r.getPackageName() != null
                        && NETFLIX.contentEquals(r.getPackageName())) list.add(r);
            }
        } catch (Exception ignored) { }
        if (list.isEmpty()) {
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r != null) list.add(r);
        }
        return list;
    }

    private void dumpWindows() {
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                AccessibilityNodeInfo r = w.getRoot();
                Rect wb = new Rect(); w.getBoundsInScreen(wb);
                String pkg = (r != null && r.getPackageName() != null) ? r.getPackageName().toString() : "null";
                List<AccessibilityNodeInfo> sc = new ArrayList<>();
                if (r != null) gatherScrollable(r, sc);
                Log.d(TAG, "WIN id=" + w.getId() + " type=" + w.getType() + " " + wb.toShortString()
                        + " root=" + (r != null) + " pkg=" + pkg + " scroll=" + sc.size()
                        + " kids=" + (r != null ? r.getChildCount() : -1));
            }
        } catch (Exception e) { Log.e(TAG, "dumpWindows", e); }
    }

    /** On-screen keyboard key centres (fraction of screen). The LatinIME exposes no key nodes,
     *  so when it's up we add these as navigation targets; OK taps them (click falls back to a tap). */
    private static final float[][] KEYS = {
        {0.045f,0.656f},{0.134f,0.656f},{0.224f,0.656f},{0.314f,0.656f},{0.404f,0.656f},
        {0.494f,0.656f},{0.584f,0.656f},{0.673f,0.656f},{0.765f,0.656f},{0.854f,0.656f},{0.950f,0.656f},
        {0.090f,0.757f},{0.180f,0.757f},{0.270f,0.757f},{0.359f,0.757f},{0.449f,0.757f},
        {0.539f,0.757f},{0.629f,0.757f},{0.719f,0.757f},{0.810f,0.757f},{0.928f,0.757f},
        {0.049f,0.858f},{0.145f,0.858f},{0.235f,0.858f},{0.325f,0.858f},{0.415f,0.858f},
        {0.505f,0.858f},{0.595f,0.858f},{0.684f,0.858f},{0.774f,0.858f},{0.864f,0.858f},{0.954f,0.858f},
        {0.049f,0.958f},{0.145f,0.958f},{0.235f,0.958f},{0.550f,0.958f},{0.864f,0.958f},
    };
    private static final String[] KEY_CHARS = {
        "q","w","e","r","t","y","u","i","o","p","DEL",
        "a","s","d","f","g","h","j","k","l","SKIP",
        "SKIP","z","x","c","v","b","n","m","!","?","SKIP",
        "SKIP",",","SKIP"," ",".",
    };

    private boolean isImeUp() {
        try {
            for (AccessibilityWindowInfo w : getWindows())
                if (w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return true;
        } catch (Exception ignored) { }
        return false;
    }

    private boolean imeUp = false;   // on-screen keyboard present (set in collectClickable)
    private List<Rect> cachedRects;
    private long cacheTime = 0;
    /** Scan all Netflix windows for clickable nodes, cached ~120ms to avoid re-walking the
     *  (large) tree on every key press. The cache is dropped on any window/content change. */
    private List<Rect> collectClickable() {
        long now = android.os.SystemClock.uptimeMillis();
        if (cachedRects != null && now - cacheTime < 120) return cachedRects;
        List<Rect> out = new ArrayList<>();
        int[] sz = screenSize();
        for (AccessibilityNodeInfo root : netflixRoots()) collect(root, out, sz[0], sz[1]);
        imeUp = isImeUp();
        if (imeUp) {                                      // add the on-screen keyboard keys
            for (float[] k : KEYS) {
                int cx = (int) (sz[0] * k[0]), cy = (int) (sz[1] * k[1]);
                out.add(new Rect(cx - 44, cy - 27, cx + 44, cy + 27));
            }
        }
        cachedRects = out; cacheTime = now;
        return out;
    }

    private void collect(AccessibilityNodeInfo node, List<Rect> out, int sw, int sh) {
        if (node == null) return;
        try {
            if (node.isVisibleToUser() && (node.isClickable() || node.isCheckable())) {
                Rect r = new Rect(); node.getBoundsInScreen(r);
                // On-screen, and tall enough to be a real control (drop thin clipped-row slivers,
                // but keep small toolbar icons like search/download at the very top).
                if (r.width() > 8 && r.height() >= 24 && r.right > 0 && r.left < sw
                        && r.top < sh && r.bottom > 0) out.add(r);
            }
            for (int i = 0; i < node.getChildCount(); i++) collect(node.getChild(i), out, sw, sh);
        } catch (Exception ignored) { }
    }

    // ---------------- gestures (fallback) ----------------
    private void tap(int x, int y) {
        Path p = new Path(); p.moveTo(x, y); p.lineTo(x + 1, y + 1);
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(p, 0, 80));
        dispatchGesture(b.build(), null, null);
    }

    private void swipe(int dx, int dy) {
        int[] sz = screenSize();
        int cx = sz[0] / 2, cy = sz[1] / 2;
        int ox = (int) (sz[0] * 0.33) * dx, oy = (int) (sz[1] * 0.33) * dy;
        Path p = new Path(); p.moveTo(cx + ox, cy + oy); p.lineTo(cx - ox, cy - oy);
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(p, 0, 220));
        dispatchGesture(b.build(), null, null);
    }

    // ---------------- overlay ----------------
    private void addOverlay() {
        if (overlayAdded || wm == null) return;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        try { wm.addView(highlight, lp); overlayAdded = true; Log.d(TAG, "overlay added"); }
        catch (Exception e) { Log.e(TAG, "overlay add failed", e); }
    }
    private void hideOverlay() {
        if (!overlayAdded || wm == null) return;
        try { wm.removeView(highlight); } catch (Exception ignored) { }
        overlayAdded = false;
    }

    private int[] screenSize() {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        return new int[]{ dm.widthPixels, dm.heightPixels };
    }

    static class HighlightView extends View {
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private boolean show = false;
        private final int sw, sh;
        HighlightView(android.content.Context c) {
            super(c);
            setLayerType(LAYER_TYPE_SOFTWARE, null);            // needed for the blurred glow
            stroke.setStyle(Paint.Style.STROKE); stroke.setStrokeWidth(3f); stroke.setColor(Color.WHITE);
            glow.setStyle(Paint.Style.STROKE); glow.setStrokeWidth(5f); glow.setColor(0x40FFFFFF);
            glow.setMaskFilter(new android.graphics.BlurMaskFilter(9f, android.graphics.BlurMaskFilter.Blur.NORMAL));
            android.util.DisplayMetrics dm = c.getResources().getDisplayMetrics();
            sw = dm.widthPixels; sh = dm.heightPixels;
        }
        // Hug the item tightly, but where the item is clipped by a screen edge push the ring
        // past that edge so no border is drawn along the cut (the item continues off-screen).
        void set(Rect r) {
            float l = r.left - 3, t = r.top - 3, rr = r.right + 3, b = r.bottom + 3;
            if (r.left   <= 0)  l  = -40;
            if (r.top    <= 0)  t  = -40;
            if (r.right  >= sw) rr = sw + 40;
            if (r.bottom >= sh) b  = sh + 40;
            box.set(l, t, rr, b);
            show = true; postInvalidate();
        }
        void clear() { show = false; postInvalidate(); }
        final Rect clipOut = new Rect();   // a region (the bottom nav bar) to NOT draw over
        @Override protected void onDraw(Canvas canvas) {
            if (!show) return;
            float rad = Math.min(11f, Math.min(box.width(), box.height()) / 2f);   // match tile corners
            int save = canvas.save();
            // Clip the bottom strip only for a content tile (ring starts well above the strip),
            // never for a short nav-bar item that lives inside the strip.
            if (!clipOut.isEmpty() && box.top < clipOut.top - 50) canvas.clipOutRect(clipOut);
            canvas.drawRoundRect(box, rad, rad, glow);
            canvas.drawRoundRect(box, rad, rad, stroke);
            canvas.restoreToCount(save);
        }
    }
}
