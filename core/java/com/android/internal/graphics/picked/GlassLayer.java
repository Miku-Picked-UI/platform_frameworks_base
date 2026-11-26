/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 *
 * Native Glass Layer: the system surfaces of DrDisagree's Glass Layer module
 * (github.com/Mahmud0808/GlassLayer, GPL-3.0) that Miku-Picked UI had no glass on, rewritten as
 * platform code. The module hooks View.draw from Xposed and captures the screen itself; here a
 * View carries a PickedDrawDecoration and the glass is SurfaceFlinger's Liquid Glass blur region
 * (persist.sys.penguin.liquid_glass) in windows that float over something, or a static tint, rim
 * and sheen inside opaque app windows, like the module does in apps.
 */

package com.android.internal.graphics.picked;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityThread;
import android.app.Application;
import android.content.ContentResolver;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Glass Layer settings and the entry points that turn views into glass.
 *
 * <p>Settings.Secure keys: {@link #KEY_ENABLED} (master switch, default off), {@code
 * picked_glass_layer_<surface>} (each surface, default on), {@link #KEY_TINT} (how opaque the
 * glass tint is, percent of each surface's default, 0-200), {@link #KEY_BLUR} (blur radius in
 * dp), {@link #KEY_RIM} (lit rim and sheen).
 *
 * @hide
 */
public final class GlassLayer {

    public static final String KEY_ENABLED = "picked_glass_layer";
    public static final String KEY_TINT = "picked_glass_layer_tint";
    public static final String KEY_BLUR = "picked_glass_layer_blur";
    public static final String KEY_RIM = "picked_glass_layer_rim";

    // Surfaces, the keys suffix and Glass Layer's names for them.
    public static final String TOASTS = "toasts";
    public static final String DIALOGS = "dialogs";
    public static final String POWER_MENU = "power_menu";
    public static final String VOLUME = "volume";
    public static final String STATUS_BAR_CHIPS = "status_bar_chips";
    public static final String LOCKSCREEN_SHORTCUTS = "lockscreen_shortcuts";
    public static final String SETTINGS_CARDS = "settings_cards";
    public static final String INSTALLER_DIALOGS = "installer_dialogs";

    private static final String[] SURFACES = {
        TOASTS, DIALOGS, POWER_MENU, VOLUME, STATUS_BAR_CHIPS, LOCKSCREEN_SHORTCUTS,
        SETTINGS_CARDS, INSTALLER_DIALOGS,
    };

    /** Glass Layer's default blur radius, 12 dp. */
    public static final int DEFAULT_BLUR_DP = 12;

    private static final Object sLock = new Object();
    private static boolean sRegistered;
    private static volatile boolean sEnabled;
    private static volatile float sTintScale = 1f;
    private static volatile int sBlurDp = DEFAULT_BLUR_DP;
    private static volatile boolean sRim = true;
    private static final Set<String> sOff = Collections.synchronizedSet(new java.util.HashSet<>());
    private static final Set<View> sViews = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<View> sWatchedRoots = Collections.newSetFromMap(new WeakHashMap<>());
    private static final ArrayList<Runnable> sListeners = new ArrayList<>();
    private static Handler sMain;

    private GlassLayer() {}

    /** Whether Glass Layer and the given surface are on. */
    public static boolean isActive(@NonNull String surface) {
        ensureRegistered();
        return sEnabled && !sOff.contains(surface);
    }

    /** The user's tint scale (1 = Glass Layer's defaults). */
    public static float tintScale() {
        ensureRegistered();
        return sTintScale;
    }

    public static int blurDp() {
        ensureRegistered();
        return sBlurDp;
    }

    public static boolean rim() {
        ensureRegistered();
        return sRim;
    }

    /** Runs the listener on the main thread whenever a Glass Layer setting changes. */
    public static void addListener(@NonNull Runnable listener) {
        ensureRegistered();
        synchronized (sListeners) {
            sListeners.add(listener);
        }
    }

    /**
     * Turns one view into glass: its solid background keeps {@code tint} of its opacity, with the
     * glass under it.
     *
     * @param flags {@link GlassSurface#LIVE}, {@link GlassSurface#RIM_ON_TOP}, ...
     */
    public static void attach(@NonNull View view, @NonNull String surface, float tint, int flags) {
        ensureRegistered();
        View.PickedDrawDecoration current = view.getPickedDrawDecoration();
        if (current instanceof GlassSurface glass) {
            glass.update(surface, tint, flags);
            view.invalidate();
        } else {
            view.setPickedDrawDecoration(new GlassSurface(surface, tint, flags));
        }
        synchronized (sViews) {
            sViews.add(view);
        }
    }

    /** Removes the glass from a view and gives its background its own opacity back. */
    public static void detach(@NonNull View view) {
        if (view.getPickedDrawDecoration() instanceof GlassSurface glass) {
            glass.release(view);
            view.setPickedDrawDecoration(null);
        }
        synchronized (sViews) {
            sViews.remove(view);
        }
    }

    public static boolean isAttached(@NonNull View view) {
        return view.getPickedDrawDecoration() instanceof GlassSurface;
    }

    /** Picks the tint for each view with a solid background in a tree, or NaN to leave it. */
    public interface TintRule {
        float tintFor(@NonNull View view, @NonNull View root);
    }

    /**
     * Turns every view with a solid shape background under {@code root} into glass, also the
     * ones added later (watched on layout), like Glass Layer does for dialogs.
     */
    public static void attachTree(@NonNull View root, @NonNull String surface, int flags,
            @NonNull TintRule rule) {
        ensureRegistered();
        attachTreeNow(root, root, surface, flags, rule);
        synchronized (sWatchedRoots) {
            if (!sWatchedRoots.add(root)) return;
        }
        root.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        attachTreeNow(root, root, surface, flags, rule);
                    }
                });
    }

    private static void attachTreeNow(View view, View root, String surface, int flags,
            TintRule rule) {
        if (!isAttached(view) && SolidShapes.of(view.getBackground()) != null) {
            float tint = rule.tintFor(view, root);
            if (!Float.isNaN(tint)) attach(view, surface, tint, flags);
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                attachTreeNow(group.getChildAt(i), root, surface, flags, rule);
            }
        }
    }

    /** Glass Layer's default installer dialog tint and its DialogGlass button tints. */
    private static final float INSTALLER_TINT = 0.65f;
    private static final float INSTALLER_BUTTON_TINT = 0.50f;
    private static final float INSTALLER_ACCENT_TINT = 0.70f;

    /** Called by Dialog.show(): the package installer's dialogs become glass. */
    public static void onDialogShown(@NonNull android.content.Context context, @Nullable View decor) {
        if (decor == null) return;
        String pkg = context.getPackageName();
        if (!"com.android.packageinstaller".equals(pkg)
                && !"com.google.android.packageinstaller".equals(pkg)) {
            return;
        }
        attachTree(decor, INSTALLER_DIALOGS, GlassSurface.LIVE, (view, root) -> {
            if (view.getWidth() <= 0) return Float.NaN;
            if (view == root) return INSTALLER_TINT;
            if (isAccent(view)) return INSTALLER_ACCENT_TINT;
            return view instanceof ViewGroup ? 0f : INSTALLER_BUTTON_TINT;
        });
    }

    // Glass Layer's AccentColors: a saturated fill, or a light fill under dark text.
    private static final float ACCENT_SATURATION = 0.4f;
    private static final float ACCENT_VALUE = 0.3f;
    private static final float LIGHT_FILL = 0.5f;
    private static final float DARK_TEXT = 0.3f;

    /** Whether the view's background reads as an accent (a filled, primary button). */
    public static boolean isAccent(@NonNull View view) {
        int color;
        if (view.getBackgroundTintList() != null) {
            color = view.getBackgroundTintList().getColorForState(view.getDrawableState(),
                    view.getBackgroundTintList().getDefaultColor());
        } else {
            android.graphics.drawable.GradientDrawable shape =
                    SolidShapes.of(view.getBackground());
            if (shape == null || shape.getColor() == null) return false;
            color = shape.getColor().getColorForState(view.getDrawableState(),
                    shape.getColor().getDefaultColor());
        }
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        if (hsv[1] >= ACCENT_SATURATION && hsv[2] >= ACCENT_VALUE) return true;
        if (Color.luminance(color) < LIGHT_FILL) return false;
        Integer text = textColor(view, 0);
        return text != null && Color.luminance(text) <= DARK_TEXT;
    }

    @Nullable
    private static Integer textColor(View view, int depth) {
        if (view instanceof TextView text) return text.getCurrentTextColor();
        if (!(view instanceof ViewGroup group) || depth >= 4) return null;
        for (int i = 0; i < group.getChildCount(); i++) {
            Integer color = textColor(group.getChildAt(i), depth + 1);
            if (color != null) return color;
        }
        return null;
    }

    private static void ensureRegistered() {
        if (sRegistered) return;
        Application app = ActivityThread.currentApplication();
        if (app == null) return;
        synchronized (sLock) {
            if (sRegistered) return;
            sRegistered = true;
            sMain = new Handler(Looper.getMainLooper());
            ContentResolver resolver = app.getContentResolver();
            // SystemUI follows the current user; an app reads its own user's settings.
            boolean acrossUsers = app.checkSelfPermission(
                    android.Manifest.permission.INTERACT_ACROSS_USERS_FULL)
                    == PackageManager.PERMISSION_GRANTED;
            int readUser = acrossUsers ? UserHandle.USER_CURRENT : UserHandle.myUserId();
            int observeUser = acrossUsers ? UserHandle.USER_ALL : UserHandle.myUserId();
            Runnable read = () -> {
                sEnabled = get(resolver, KEY_ENABLED, 0, readUser) == 1;
                sTintScale = Math.max(0, Math.min(200, get(resolver, KEY_TINT, 100, readUser)))
                        / 100f;
                sBlurDp = Math.max(0, Math.min(40,
                        get(resolver, KEY_BLUR, DEFAULT_BLUR_DP, readUser)));
                sRim = get(resolver, KEY_RIM, 1, readUser) == 1;
                for (String surface : SURFACES) {
                    if (get(resolver, KEY_ENABLED + "_" + surface, 1, readUser) == 1) {
                        sOff.remove(surface);
                    } else {
                        sOff.add(surface);
                    }
                }
            };
            read.run();
            ContentObserver observer = new ContentObserver(sMain) {
                @Override
                public void onChange(boolean selfChange, @Nullable Uri uri) {
                    read.run();
                    refresh();
                }
            };
            ArrayList<String> keys = new ArrayList<>();
            keys.add(KEY_ENABLED);
            keys.add(KEY_TINT);
            keys.add(KEY_BLUR);
            keys.add(KEY_RIM);
            for (String surface : SURFACES) keys.add(KEY_ENABLED + "_" + surface);
            for (String key : keys) {
                try {
                    resolver.registerContentObserver(Settings.Secure.getUriFor(key), false,
                            observer, observeUser);
                } catch (RuntimeException e) {
                    // An app without access to other users' settings: its own user is enough.
                }
            }
        }
    }

    private static int get(ContentResolver resolver, String key, int def, int user) {
        try {
            return Settings.Secure.getIntForUser(resolver, key, def, user);
        } catch (RuntimeException e) {
            return def;
        }
    }

    private static void refresh() {
        View[] views;
        synchronized (sViews) {
            views = sViews.toArray(new View[0]);
        }
        for (View view : views) {
            if (view != null) view.invalidate();
        }
        Runnable[] listeners;
        synchronized (sListeners) {
            listeners = sListeners.toArray(new Runnable[0]);
        }
        for (Runnable listener : listeners) listener.run();
    }
}
