/*
 * Copyright (C) 2026 The PenguinOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.globalactions;

import android.app.Dialog;
import android.content.Context;
import android.content.res.Resources;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ListAdapter;

import androidx.annotation.NonNull;

import com.android.systemui.globalactions.GlobalActionsDialogLite.Action;
import com.android.systemui.globalactions.GlobalActionsDialogLite.SinglePressAction;
import com.android.systemui.res.R;

/**
 * [PenguinOS] The iOS style power menu: big "slide to" rows instead of the grid. This class
 * builds its submenus (power options, Miku's restart options); the main menu is laid out by
 * GlobalActionsDialogLite.ActionsDialogLite. Settings.Secure power_menu_style: 0 our own power
 * menu, 1 iOS.
 */
public class GlobalActionsIosPowerDialog {

    public static final String SETTING_NAME = "power_menu_style";
    public static final int STYLE_IOS = 1;

    /** Whether the iOS style is chosen, read each time the menu opens. */
    public static boolean isActive(@NonNull Context context) {
        return Settings.Secure.getIntForUser(context.getContentResolver(), SETTING_NAME, 0,
                UserHandle.USER_CURRENT) == STYLE_IOS;
    }

    /**
     * Blurs what is behind the window when the device can, and otherwise gives it the opaque
     * backdrop, so the white rows stay readable over any wallpaper.
     */
    static void applyBackdrop(@NonNull Window window, @NonNull View container) {
        final Context context = window.getContext();
        final WindowManager wm = context.getSystemService(WindowManager.class);
        if (wm != null && wm.isCrossWindowBlurEnabled()) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
            WindowManager.LayoutParams attrs = window.getAttributes();
            attrs.setBlurBehindRadius(context.getResources().getDimensionPixelSize(
                    R.dimen.power_menu_ios_blur_radius));
            window.setAttributes(attrs);
            container.setBackgroundColor(context.getColor(R.color.power_menu_ios_blur_tint));
        } else {
            container.setBackgroundResource(R.drawable.power_menu_ios_backdrop);
        }
    }

    public static Dialog create(@NonNull Context context, @NonNull ListAdapter adapter) {
        final Resources res = context.getResources();
        final LayoutInflater inflater = LayoutInflater.from(context);
        final ViewGroup content = (ViewGroup) inflater.inflate(
                R.layout.global_actions_ios_submenu, null);

        final Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(content);

        final LinearLayout rows = content.findViewById(R.id.power_menu_ios_submenu_content);
        final int track = res.getColor(R.color.power_menu_ios_track, null);
        final int thumb = res.getColor(R.color.power_menu_ios_thumb, null);
        final int foreground = res.getColor(R.color.power_menu_ios_foreground, null);
        final int rowGap = res.getDimensionPixelSize(R.dimen.power_menu_ios_row_gap);

        for (int i = 0; i < adapter.getCount(); i++) {
            Object item = adapter.getItem(i);
            if (!(item instanceof Action)) {
                continue;
            }
            final Action action = (Action) item;
            SlideToActView row = new SlideToActView(context, null);
            row.setColors(track, thumb, foreground);
            row.setLabel(getLabel(context, action));
            row.setThumbIcon(action.getIcon(context));
            row.setOnSlideCompleteListener(v -> {
                dialog.dismiss();
                action.onPress();
            });

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            if (rows.getChildCount() > 0) {
                params.topMargin = rowGap;
            }
            rows.addView(row, params);
        }

        content.findViewById(R.id.power_menu_ios_close).setOnClickListener(v -> dialog.dismiss());

        final Window window = dialog.getWindow();
        window.setType(WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY);
        window.setTitle("");
        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        window.setBackgroundDrawableResource(android.R.color.transparent);
        applyBackdrop(window, content);

        return dialog;
    }

    static CharSequence getLabel(@NonNull Context context, @NonNull Action action) {
        if (action instanceof SinglePressAction) {
            SinglePressAction singlePress = (SinglePressAction) action;
            if (singlePress.getMessage() != null) {
                return singlePress.getMessage();
            }
            if (singlePress.getMessageResId() != 0) {
                return context.getString(singlePress.getMessageResId());
            }
        }
        return action.getLabelForAccessibility(context);
    }

    private GlobalActionsIosPowerDialog() {
    }
}
