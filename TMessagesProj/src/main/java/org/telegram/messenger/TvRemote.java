/*
 * TVgram: remote-control (D-pad) support for Android TV.
 * Licensed under GNU GPL v. 2 or later, like the rest of this project.
 *
 * Idea: Telegram's UI is built for touch. Instead of rewriting every screen for
 * focus navigation, we draw an on-screen cursor that the remote's arrows move,
 * and turn OK into real touch events (tap, long press, drag). Channel +/- and
 * pushing the cursor against a screen edge scroll the list under the cursor.
 */
package org.telegram.messenger;

import android.app.Activity;
import android.app.UiModeManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.WeakHashMap;

public class TvRemote {

    private static Boolean isTv;

    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final WeakHashMap<View, Boolean> hookedRoots = new WeakHashMap<>();
    private static WeakReference<Activity> activityRef;

    private static float cursorX = -1, cursorY = -1;
    private static boolean pressed;
    private static long pressDownTime;
    private static WeakReference<View> pressRoot;
    private static WeakReference<View> overlayHost;
    private static CursorDrawable cursor;
    private static long lastEdgeScroll;
    private static boolean polling;

    private static final long HIDE_DELAY = 5000;
    private static final long POLL_INTERVAL = 350;

    /** True on Android TV / boxes without a touchscreen. */
    public static boolean isTv() {
        if (isTv == null) {
            boolean tv = false;
            try {
                Context c = ApplicationLoader.applicationContext;
                UiModeManager ui = (UiModeManager) c.getSystemService(Context.UI_MODE_SERVICE);
                PackageManager pm = c.getPackageManager();
                tv = ui != null && ui.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION
                        || pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
                        || pm.hasSystemFeature("android.software.leanback_only")
                        || !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
            } catch (Throwable e) {
                FileLog.e(e);
            }
            isTv = tv;
        }
        return isTv;
    }

    public static boolean isEnabled() {
        return isTv();
    }

    // ---------------------------------------------------------------- TV storage defaults

    /**
     * TV boxes have little free storage. On first start: cache limit 300 MB,
     * keep media 1 week for private chats and 1 day for groups / channels / stories.
     * Applied once; the user can change everything in Settings > Data and Storage.
     */
    public static void applyTvGlobalDefaults() {
        try {
            if (!isTv()) return;
            android.content.SharedPreferences p = SharedConfig.getPreferences();
            if (p.getBoolean("tvgram_storage_defaults_v1", false)) return;
            p.edit()
                    .putInt("cache_limit", 1) // 1 = 300 MB in AutoDeleteMediaTask / CacheControlActivity
                    .putInt("keep_media_type_" + CacheByChatsController.KEEP_MEDIA_TYPE_USER, CacheByChatsController.KEEP_MEDIA_ONE_WEEK)
                    .putInt("keep_media_type_" + CacheByChatsController.KEEP_MEDIA_TYPE_GROUP, CacheByChatsController.KEEP_MEDIA_ONE_DAY)
                    .putInt("keep_media_type_" + CacheByChatsController.KEEP_MEDIA_TYPE_CHANNEL, CacheByChatsController.KEEP_MEDIA_ONE_DAY)
                    .putInt("keep_media_type_" + CacheByChatsController.KEEP_MEDIA_TYPE_STORIES, CacheByChatsController.KEEP_MEDIA_ONE_DAY)
                    .putBoolean("tvgram_storage_defaults_v1", true)
                    .apply();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** Per logged-in account: no auto-download / preloading of videos and files (they open on demand). */
    private static void applyTvAccountDefaults() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            try {
                if (!UserConfig.getInstance(a).isClientActivated()) continue;
                android.content.SharedPreferences s = MessagesController.getMainSettings(a);
                if (s.getBoolean("tvgram_autodownload_v1", false)) continue;
                DownloadController dc = DownloadController.getInstance(a);
                DownloadController.Preset[] presets = {dc.mobilePreset, dc.wifiPreset, dc.roamingPreset};
                String[] keys = {"mobilePreset", "wifiPreset", "roamingPreset"};
                android.content.SharedPreferences.Editor e = s.edit();
                for (int i = 0; i < presets.length; i++) {
                    DownloadController.Preset pr = presets[i];
                    for (int j = 0; j < pr.mask.length; j++) {
                        pr.mask[j] &= ~(DownloadController.AUTODOWNLOAD_TYPE_VIDEO | DownloadController.AUTODOWNLOAD_TYPE_DOCUMENT);
                    }
                    pr.preloadVideo = false;
                    pr.preloadMusic = false;
                    pr.preloadStories = false;
                    e.putString(keys[i], pr.toString());
                }
                // 3 = custom preset, so presets from the server do not overwrite ours
                e.putInt("currentMobilePreset", dc.currentMobilePreset = 3);
                e.putInt("currentWifiPreset", dc.currentWifiPreset = 3);
                e.putInt("currentRoamingPreset", dc.currentRoamingPreset = 3);
                e.putBoolean("tvgram_autodownload_v1", true);
                e.apply();
                dc.checkAutodownloadSettings();
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
    }

    // ---------------------------------------------------------------- lifecycle

    public static void attach(Activity activity) {
        if (!isEnabled()) return;
        applyTvAccountDefaults();
        activityRef = new WeakReference<>(activity);
        if (!polling) {
            polling = true;
            handler.post(pollRunnable);
        }
    }

    public static void detach(Activity activity) {
        if (activityRef != null && activityRef.get() == activity) {
            polling = false;
            handler.removeCallbacks(pollRunnable);
            if (pressed) cancelPress();
            hideCursor();
        }
    }

    /** Hooks windows that do not route keys through an Activity/Dialog we patched (popups, viewers). */
    private static final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!polling) return;
            if (Build.VERSION.SDK_INT >= 28) {
                List<View> roots = AndroidUtilities.allGlobalViews();
                if (roots != null) {
                    for (int i = 0; i < roots.size(); i++) {
                        View root = roots.get(i);
                        if (root == null || hookedRoots.containsKey(root)) continue;
                        hookedRoots.put(root, Boolean.TRUE);
                        try {
                            root.addOnUnhandledKeyEventListener((v, event) -> dispatchKeyEvent(event));
                        } catch (Throwable e) {
                            FileLog.e(e);
                        }
                    }
                }
            }
            if (cursor != null && cursor.visible) {
                // window stack may have changed (dialog opened/closed): keep cursor on top
                attachCursorToHost();
            }
            handler.postDelayed(this, POLL_INTERVAL);
        }
    };

    // ---------------------------------------------------------------- keys

    /**
     * Call first thing from dispatchKeyEvent of an Activity, Dialog or window root view.
     * @return true if the key was consumed by the remote cursor.
     */
    public static boolean dispatchKeyEvent(@Nullable KeyEvent event) {
        if (event == null || !isEnabled()) return false;
        // keys produced by an on-screen keyboard (IME) belong to the text field
        if ((event.getFlags() & KeyEvent.FLAG_SOFT_KEYBOARD) != 0 || event.getDeviceId() == KeyCharacterMap.VIRTUAL_KEYBOARD) {
            return false;
        }
        final int code = event.getKeyCode();
        final int action = event.getAction();
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: {
                if (action == KeyEvent.ACTION_DOWN) {
                    int dx = code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
                    int dy = code == KeyEvent.KEYCODE_DPAD_UP ? -1 : code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0;
                    move(dx, dy, event.getRepeatCount());
                }
                return true;
            }
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (isTextFieldFocused()) {
                    return false; // physical keyboard typing into a text field
                }
                // fall through
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_BUTTON_A: {
                if (action == KeyEvent.ACTION_DOWN) {
                    if (event.getRepeatCount() == 0) {
                        if (pressed) cancelPress();
                        pressDown();
                    }
                } else if (action == KeyEvent.ACTION_UP) {
                    if (pressed) pressUp();
                }
                return true;
            }
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                if (action == KeyEvent.ACTION_DOWN) scroll(0, -1, 0.75f);
                return true;
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                if (action == KeyEvent.ACTION_DOWN) scroll(0, 1, 0.75f);
                return true;
        }
        return false;
    }

    private static boolean isTextFieldFocused() {
        Activity a = activityRef != null ? activityRef.get() : null;
        View f = a != null ? a.getCurrentFocus() : null;
        return f instanceof EditText;
    }

    // ---------------------------------------------------------------- cursor movement

    private static void move(int dx, int dy, int repeat) {
        int[] screen = screenSize();
        if (screen == null) return;
        ensureCursorPosition(screen);
        boolean wasVisible = cursor != null && cursor.visible;
        if (!wasVisible && !pressed) {
            showCursor(); // first press only wakes the cursor up
            return;
        }
        float speed = AndroidUtilities.dp(9) * Math.min(1f + repeat * 0.4f, 6f);
        float nx = cursorX + dx * speed;
        float ny = cursorY + dy * speed;
        boolean atEdge = (dx < 0 && cursorX <= 0) || (dx > 0 && cursorX >= screen[0] - 1)
                || (dy < 0 && cursorY <= 0) || (dy > 0 && cursorY >= screen[1] - 1);
        cursorX = Math.max(0, Math.min(screen[0] - 1, nx));
        cursorY = Math.max(0, Math.min(screen[1] - 1, ny));
        if (pressed) {
            dispatchPointer(MotionEvent.ACTION_MOVE);
        } else if (atEdge) {
            long now = SystemClock.uptimeMillis();
            if (now - lastEdgeScroll > 220) {
                lastEdgeScroll = now;
                scroll(dx, dy, 0.35f);
            }
        }
        showCursor();
    }

    private static void ensureCursorPosition(int[] screen) {
        if (cursorX < 0 || cursorY < 0 || cursorX >= screen[0] || cursorY >= screen[1]) {
            cursorX = screen[0] / 2f;
            cursorY = screen[1] / 2f;
        }
    }

    @Nullable
    private static int[] screenSize() {
        Activity a = activityRef != null ? activityRef.get() : null;
        if (a != null && a.getWindow() != null) {
            View decor = a.getWindow().getDecorView();
            if (decor.getWidth() > 0 && decor.getHeight() > 0) {
                int[] loc = new int[2];
                decor.getLocationOnScreen(loc);
                return new int[]{loc[0] + decor.getWidth(), loc[1] + decor.getHeight()};
            }
        }
        if (AndroidUtilities.displaySize.x > 0) {
            return new int[]{AndroidUtilities.displaySize.x, AndroidUtilities.displaySize.y};
        }
        return null;
    }

    // ---------------------------------------------------------------- windows

    @Nullable
    private static List<View> roots() {
        List<View> roots = AndroidUtilities.allGlobalViews();
        if (roots == null || roots.isEmpty()) {
            Activity a = activityRef != null ? activityRef.get() : null;
            if (a != null && a.getWindow() != null) {
                roots = new java.util.ArrayList<>();
                roots.add(a.getWindow().getDecorView());
            }
        }
        return roots;
    }

    private static boolean isUsable(View root) {
        return root != null && root.isAttachedToWindow() && root.getVisibility() == View.VISIBLE
                && root.getWindowVisibility() == View.VISIBLE && root.getWidth() > 0 && root.getHeight() > 0;
    }

    private static int flagsOf(View root) {
        ViewGroup.LayoutParams lp = root.getLayoutParams();
        return lp instanceof WindowManager.LayoutParams ? ((WindowManager.LayoutParams) lp).flags : 0;
    }

    private static boolean contains(View root, float x, float y) {
        int[] loc = new int[2];
        root.getLocationOnScreen(loc);
        return x >= loc[0] && x < loc[0] + root.getWidth() && y >= loc[1] && y < loc[1] + root.getHeight();
    }

    /** The window that would receive a real touch at the given screen point. */
    @Nullable
    private static View touchTarget(float x, float y) {
        List<View> roots = roots();
        if (roots == null) return null;
        for (int i = roots.size() - 1; i >= 0; i--) {
            View root = roots.get(i);
            if (!isUsable(root)) continue;
            int flags = flagsOf(root);
            if ((flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0) continue;
            if (contains(root, x, y)) return root;
            boolean touchModal = (flags & WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL) == 0
                    && (flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0;
            if (touchModal) return root; // modal window eats touches outside of it (and usually closes)
        }
        return null;
    }

    /** Topmost window that geometrically covers the point: the cursor is drawn there. */
    @Nullable
    private static View drawTarget(float x, float y) {
        List<View> roots = roots();
        if (roots == null) return null;
        for (int i = roots.size() - 1; i >= 0; i--) {
            View root = roots.get(i);
            if (isUsable(root) && contains(root, x, y)) return root;
        }
        return null;
    }

    // ---------------------------------------------------------------- touch injection

    private static void pressDown() {
        int[] screen = screenSize();
        if (screen == null) return;
        ensureCursorPosition(screen);
        View target = touchTarget(cursorX, cursorY);
        if (target == null) return;
        // windows above the target that watch outside touches (popups) get ACTION_OUTSIDE, like real touches
        List<View> roots = roots();
        if (roots != null) {
            boolean above = false;
            for (int i = 0; i < roots.size(); i++) {
                View r = roots.get(i);
                if (r == target) {
                    above = true;
                    continue;
                }
                if (above && isUsable(r) && (flagsOf(r) & WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH) != 0) {
                    long t = SystemClock.uptimeMillis();
                    MotionEvent outside = MotionEvent.obtain(t, t, MotionEvent.ACTION_OUTSIDE, 0, 0, 0);
                    outside.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                    try {
                        r.dispatchTouchEvent(outside);
                    } catch (Throwable e) {
                        FileLog.e(e);
                    }
                    outside.recycle();
                }
            }
        }
        pressRoot = new WeakReference<>(target);
        pressDownTime = SystemClock.uptimeMillis();
        pressed = true;
        dispatchPointer(MotionEvent.ACTION_DOWN);
        showCursor();
    }

    private static void pressUp() {
        dispatchPointer(MotionEvent.ACTION_UP);
        pressed = false;
        pressRoot = null;
        showCursor();
    }

    private static void cancelPress() {
        dispatchPointer(MotionEvent.ACTION_CANCEL);
        pressed = false;
        pressRoot = null;
    }

    private static void dispatchPointer(int action) {
        View root = pressRoot != null ? pressRoot.get() : null;
        if (root == null || !root.isAttachedToWindow()) return;
        int[] loc = new int[2];
        root.getLocationOnScreen(loc);
        dispatchTo(root, action, pressDownTime, cursorX - loc[0], cursorY - loc[1]);
    }

    private static void dispatchTo(View root, int action, long downTime, float x, float y) {
        MotionEvent ev = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0);
        ev.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try {
            root.dispatchTouchEvent(ev);
        } catch (Throwable e) {
            FileLog.e(e);
        }
        ev.recycle();
    }

    // ---------------------------------------------------------------- scrolling

    private static void scroll(int dx, int dy, float fraction) {
        if (pressed) return;
        int[] screen = screenSize();
        if (screen == null) return;
        ensureCursorPosition(screen);
        View root = touchTarget(cursorX, cursorY);
        if (root == null) return;
        int[] loc = new int[2];
        root.getLocationOnScreen(loc);
        View target = scrollableAt(root, cursorX - loc[0], cursorY - loc[1], dx, dy);
        if (target == null) target = largestScrollable(root, dx, dy);
        if (target == null) return;
        int distX = (int) (dx * target.getWidth() * fraction);
        int distY = (int) (dy * target.getHeight() * fraction);
        if (target instanceof RecyclerView) {
            ((RecyclerView) target).smoothScrollBy(distX, distY);
            return;
        }
        // generic view: drag a "finger" across its middle, slowly, so it scrolls without flinging
        int[] tl = new int[2];
        target.getLocationOnScreen(tl);
        float sx = tl[0] - loc[0] + target.getWidth() / 2f + distX / 2f;
        float sy = tl[1] - loc[1] + target.getHeight() / 2f + distY / 2f;
        dragGesture(root, sx, sy, sx - distX, sy - distY);
    }

    private static void dragGesture(View root, float fromX, float fromY, float toX, float toY) {
        final long down = SystemClock.uptimeMillis();
        dispatchTo(root, MotionEvent.ACTION_DOWN, down, fromX, fromY);
        final int steps = 8;
        for (int i = 1; i <= steps; i++) {
            final float p = i / (float) steps;
            handler.postDelayed(() -> dispatchTo(root, MotionEvent.ACTION_MOVE, down, fromX + (toX - fromX) * p, fromY + (toY - fromY) * p), i * 16L);
        }
        // hold still before lifting, so the release has ~zero velocity (no fling)
        handler.postDelayed(() -> dispatchTo(root, MotionEvent.ACTION_MOVE, down, toX, toY), steps * 16L + 90);
        handler.postDelayed(() -> dispatchTo(root, MotionEvent.ACTION_UP, down, toX, toY), steps * 16L + 120);
    }

    private static boolean canScroll(View v, int dx, int dy) {
        return (dy != 0 && v.canScrollVertically(dy)) || (dx != 0 && v.canScrollHorizontally(dx));
    }

    /** Deepest visible view under (x, y) (in v's coordinates) that can scroll in the direction. */
    @Nullable
    private static View scrollableAt(View v, float x, float y, int dx, int dy) {
        if (v.getVisibility() != View.VISIBLE) return null;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = g.getChildCount() - 1; i >= 0; i--) {
                View c = g.getChildAt(i);
                if (c.getVisibility() != View.VISIBLE) continue;
                float cx = x + g.getScrollX() - c.getLeft() - c.getTranslationX();
                float cy = y + g.getScrollY() - c.getTop() - c.getTranslationY();
                if (cx >= 0 && cy >= 0 && cx < c.getWidth() && cy < c.getHeight()) {
                    View found = scrollableAt(c, cx, cy, dx, dy);
                    if (found != null) return found;
                }
            }
        }
        return canScroll(v, dx, dy) ? v : null;
    }

    @Nullable
    private static View largestScrollable(View v, int dx, int dy) {
        View best = null;
        if (v.getVisibility() != View.VISIBLE || !v.isShown()) return null;
        if (canScroll(v, dx, dy)) best = v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = largestScrollable(g.getChildAt(i), dx, dy);
                if (c != null && (best == null || (long) c.getWidth() * c.getHeight() > (long) best.getWidth() * best.getHeight())) {
                    best = c;
                }
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- drawing

    private static final Runnable hideRunnable = TvRemote::hideCursor;

    private static void showCursor() {
        if (cursor == null) cursor = new CursorDrawable();
        cursor.visible = true;
        cursor.pressed = pressed;
        attachCursorToHost();
        handler.removeCallbacks(hideRunnable);
        if (!pressed) handler.postDelayed(hideRunnable, HIDE_DELAY);
    }

    private static void hideCursor() {
        handler.removeCallbacks(hideRunnable);
        if (cursor != null) cursor.visible = false;
        View host = overlayHost != null ? overlayHost.get() : null;
        if (host != null && cursor != null) {
            try {
                host.getOverlay().remove(cursor);
            } catch (Throwable ignore) {
            }
            host.invalidate();
        }
        overlayHost = null;
    }

    private static void attachCursorToHost() {
        if (cursor == null || !cursor.visible) return;
        View host = drawTarget(cursorX, cursorY);
        if (host == null) return;
        View old = overlayHost != null ? overlayHost.get() : null;
        if (old != host) {
            if (old != null) {
                try {
                    old.getOverlay().remove(cursor);
                } catch (Throwable ignore) {
                }
                old.invalidate();
            }
            try {
                host.getOverlay().add(cursor);
            } catch (Throwable e) {
                FileLog.e(e);
                return;
            }
            overlayHost = new WeakReference<>(host);
        }
        int[] loc = new int[2];
        host.getLocationOnScreen(loc);
        int r = AndroidUtilities.dp(14);
        int lx = (int) (cursorX - loc[0]), ly = (int) (cursorY - loc[1]);
        cursor.pressed = pressed;
        cursor.setBounds(lx - r, ly - r, lx + r, ly + r);
        host.invalidate();
    }

    private static class CursorDrawable extends Drawable {
        boolean visible;
        boolean pressed;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);

        CursorDrawable() {
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(AndroidUtilities.dp(2.5f));
            ring.setColor(0xffffffff);
            shadow.setStyle(Paint.Style.STROKE);
            shadow.setStrokeWidth(AndroidUtilities.dp(5f));
            shadow.setColor(0x66000000);
            fill.setColor(0xffff9f1a);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            if (!visible) return;
            float cx = getBounds().exactCenterX(), cy = getBounds().exactCenterY();
            float r = AndroidUtilities.dp(pressed ? 9 : 11);
            canvas.drawCircle(cx, cy, r, shadow);
            fill.setAlpha(pressed ? 255 : 170);
            canvas.drawCircle(cx, cy, r, fill);
            canvas.drawCircle(cx, cy, r, ring);
            canvas.drawCircle(cx, cy, AndroidUtilities.dp(2), ring);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
