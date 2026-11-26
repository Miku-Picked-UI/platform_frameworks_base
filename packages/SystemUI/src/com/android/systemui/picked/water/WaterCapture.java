/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.picked.water;

import android.graphics.Bitmap;
import android.view.SurfaceControl;
import android.view.WindowManagerGlobal;
import android.window.ScreenCaptureInternal;

/** The display without one layer, scaled down (as ColorCC's Hook.capture does). */
final class WaterCapture {

    private WaterCapture() {}

    /** Blocking; call off the main thread. Null when the capture failed. */
    static Bitmap capture(int displayId, SurfaceControl exclude, float scale) throws Exception {
        // Raw builder: CaptureArgs.Builder is self-typed (Builder<T extends Builder<T>>).
        @SuppressWarnings({"rawtypes", "unchecked"})
        ScreenCaptureInternal.CaptureArgs.Builder builder =
                new ScreenCaptureInternal.CaptureArgs.Builder();
        builder.setFrameScale(scale);
        builder.setExcludeLayers(new SurfaceControl[] {exclude});
        ScreenCaptureInternal.CaptureArgs args = builder.build();
        ScreenCaptureInternal.SynchronousScreenCaptureListener listener =
                ScreenCaptureInternal.createSyncCaptureListener();
        WindowManagerGlobal.getWindowManagerService().captureDisplay(displayId, args, listener);
        ScreenCaptureInternal.ScreenshotHardwareBuffer buffer = listener.getBuffer();
        return buffer == null ? null : buffer.asBitmap();
    }
}
