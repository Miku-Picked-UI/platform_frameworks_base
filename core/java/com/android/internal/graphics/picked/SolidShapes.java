/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.graphics.picked;

import android.annotation.Nullable;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableContainer;
import android.graphics.drawable.DrawableWrapper;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;

/**
 * The filled shape inside a view's background (through wrappers, state lists, layers and
 * ripples, skipping a ripple's mask), as Glass Layer's SolidShapes finds it.
 *
 * @hide
 */
public final class SolidShapes {

    private SolidShapes() {}

    @Nullable
    public static GradientDrawable of(@Nullable Drawable drawable) {
        if (drawable instanceof GradientDrawable shape) {
            return shape.getColor() != null && Color.alpha(shape.getColor().getDefaultColor()) > 0
                    ? shape : null;
        }
        if (drawable instanceof DrawableWrapper wrapper) return of(wrapper.getDrawable());
        if (drawable instanceof DrawableContainer container) return of(container.getCurrent());
        if (drawable instanceof LayerDrawable layers) {
            for (int i = 0; i < layers.getNumberOfLayers(); i++) {
                if (layers.getId(i) == android.R.id.mask) continue;
                GradientDrawable shape = of(layers.getDrawable(i));
                if (shape != null) return shape;
            }
        }
        return null;
    }
}
