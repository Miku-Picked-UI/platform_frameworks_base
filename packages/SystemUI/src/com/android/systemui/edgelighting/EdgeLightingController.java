/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.edgelighting;

import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.util.Log;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.headsup.HeadsUpManager;
import com.android.systemui.statusbar.notification.headsup.OnHeadsUpChangedListener;

import java.io.InputStream;
import java.util.concurrent.Executor;

import javax.inject.Inject;

@SysUISingleton
public class EdgeLightingController {

    private static final String TAG = "EdgeLighting";
    private static final String PACKAGE = "com.penguin.laboratory";
    private static final String AUTHORITY = PACKAGE + ".provider";
    private static final Uri STATE_URI = Uri.parse("content://" + AUTHORITY + "/state");
    private static final String ACTION_PREVIEW = PACKAGE + ".action.EDGE_LIGHTING_PREVIEW";
    private static final int ICON_SIZE_PX = 256;

    private final Context mContext;
    private final WindowManager mWindowManager;
    private final PowerManager mPowerManager;
    private final Executor mMainExecutor;
    private final Executor mBgExecutor;

    private EdgeLightingView mView;
    private boolean mPulsing;

    private static final class State {
        boolean enabled;
        String selected;
        boolean appIcon;
        boolean screenOffOnly;
        EdgeLightingView.Style style;
    }

    @Inject
    public EdgeLightingController(Context context, @Main Executor mainExecutor,
            @Background Executor bgExecutor, HeadsUpManager headsUpManager) {
        mContext = context;
        mWindowManager = context.getSystemService(WindowManager.class);
        mPowerManager = context.getSystemService(PowerManager.class);
        mMainExecutor = mainExecutor;
        mBgExecutor = bgExecutor;
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (PACKAGE.equals(getSentFromPackage())) play(null, Trigger.PREVIEW);
            }
        }, new IntentFilter(ACTION_PREVIEW), Context.RECEIVER_EXPORTED);
        headsUpManager.addListener(new OnHeadsUpChangedListener() {
            @Override
            public void onHeadsUpStateChanged(@NonNull NotificationEntry entry, boolean isHeadsUp) {
                if (isHeadsUp && !mPulsing && mPowerManager.isInteractive()) {
                    play(entry, Trigger.AWAKE);
                }
            }
        });
    }

    private enum Trigger { PULSE, AWAKE, PREVIEW }

    public void onNotificationPulseStarted(@Nullable NotificationEntry entry) {
        mPulsing = true;
        play(entry, Trigger.PULSE);
    }

    public void onPulseFinished() {
        mPulsing = false;
        if (mView != null) mView.finish();
    }

    private void play(@Nullable NotificationEntry entry, Trigger trigger) {
        mBgExecutor.execute(() -> {
            State state = loadState();
            if (state == null) return;
            if (trigger != Trigger.PREVIEW) {
                if (!state.enabled) return;
                if (trigger == Trigger.AWAKE && state.screenOffOnly) return;
            }
            Bitmap bitmap = pickImage(state, entry);
            if (bitmap == null) return;
            mMainExecutor.execute(() -> {
                if (trigger == Trigger.PULSE && !mPulsing) return;
                show(bitmap, state.style);
            });
        });
    }

    @Nullable
    private State loadState() {
        try (Cursor c = mContext.getContentResolver().query(STATE_URI, null, null, null, null)) {
            if (c == null || !c.moveToFirst()) return null;
            State s = new State();
            s.enabled = c.getInt(c.getColumnIndexOrThrow("enabled")) != 0;
            s.selected = c.getString(c.getColumnIndexOrThrow("selected"));
            s.appIcon = c.getInt(c.getColumnIndexOrThrow("app_icon")) != 0;
            s.screenOffOnly = c.getInt(c.getColumnIndexOrThrow("screen_off_only")) != 0;
            s.style = new EdgeLightingView.Style(
                    c.getString(c.getColumnIndexOrThrow("effect")),
                    c.getInt(c.getColumnIndexOrThrow("size")),
                    c.getInt(c.getColumnIndexOrThrow("amount")),
                    c.getInt(c.getColumnIndexOrThrow("duration")),
                    c.getInt(c.getColumnIndexOrThrow("rotation")));
            return s;
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Nullable
    private Bitmap pickImage(State state, @Nullable NotificationEntry entry) {
        if (entry != null && state.appIcon) {
            Bitmap icon = appIcon(entry.getSbn().getPackageName());
            if (icon != null) return icon;
        }
        String id = state.selected;
        if (entry != null) {
            String keywordImage = resolveKeyword(text(entry.getSbn().getNotification()));
            if (keywordImage != null) id = keywordImage;
        }
        return id != null ? loadImage(id) : null;
    }

    private static String text(Notification n) {
        Bundle extras = n.extras;
        if (extras == null) return "";
        return extras.getCharSequence(Notification.EXTRA_TITLE, "") + " "
                + extras.getCharSequence(Notification.EXTRA_TEXT, "") + " "
                + extras.getCharSequence(Notification.EXTRA_BIG_TEXT, "");
    }

    @Nullable
    private String resolveKeyword(String text) {
        try {
            Bundle result = mContext.getContentResolver().call(AUTHORITY, "resolve", text, null);
            return result != null ? result.getString("id") : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Nullable
    private Bitmap loadImage(String id) {
        Uri uri = Uri.parse("content://" + AUTHORITY + "/image/" + Uri.encode(id));
        try (InputStream is = mContext.getContentResolver().openInputStream(uri)) {
            return BitmapFactory.decodeStream(is);
        } catch (Exception e) {
            Log.w(TAG, "Couldn't load " + id, e);
            return null;
        }
    }

    @Nullable
    private Bitmap appIcon(String pkg) {
        try {
            Drawable icon = mContext.getPackageManager().getApplicationIcon(pkg);
            Bitmap bitmap = Bitmap.createBitmap(ICON_SIZE_PX, ICON_SIZE_PX,
                    Bitmap.Config.ARGB_8888);
            icon.setBounds(0, 0, ICON_SIZE_PX, ICON_SIZE_PX);
            icon.draw(new Canvas(bitmap));
            return bitmap;
        } catch (Exception e) {
            return null;
        }
    }

    private void show(Bitmap bitmap, EdgeLightingView.Style style) {
        if (mView != null) return;
        mView = new EdgeLightingView(mContext, bitmap, style, this::remove);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.setFitInsetsTypes(0);
        lp.setTitle("EdgeLighting");
        mWindowManager.addView(mView, lp);
    }

    private void remove() {
        if (mView == null) return;
        mWindowManager.removeViewImmediate(mView);
        mView = null;
    }
}
