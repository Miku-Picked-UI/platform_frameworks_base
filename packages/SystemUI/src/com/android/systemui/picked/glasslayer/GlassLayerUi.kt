/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 *
 * Native Glass Layer for SystemUI: where DrDisagree's Glass Layer module
 * (github.com/Mahmud0808/GlassLayer, GPL-3.0) hooks SystemUI's toasts, dialogs, power menu,
 * volume dialog, status bar chips and lockscreen shortcuts, those call in here. The glass itself
 * is the framework's com.android.internal.graphics.picked.GlassLayer (views) or the Liquid Glass
 * blur region (Compose), with Glass Layer's default tint for each surface.
 */

package com.android.systemui.picked.glasslayer

import android.app.Dialog
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.android.internal.graphics.picked.GlassLayer
import com.android.internal.graphics.picked.GlassSurface
import com.android.internal.graphics.picked.SolidShapes

object GlassLayerUi {

    // Glass Layer's default tints (PrefKeys.DEFAULT_*), as a fraction of the stock opacity.
    private const val TOAST_TINT = 0.50f
    private const val DIALOG_TINT = 0.60f
    private const val DIALOG_BUTTON_TINT = 0.50f
    private const val DIALOG_ACCENT_TINT = 0.70f
    private const val DIALOG_ACTIVE_TINT = 0.85f
    private const val POWER_MENU_TINT = 0.40f
    private const val POWER_MENU_BUTTON_TINT = 0.30f
    private const val POWER_MENU_ACCENT_TINT = 0.70f
    private const val VOLUME_TINT = 0.40f
    private const val VOLUME_BUTTON_TINT = 0.30f
    private const val VOLUME_ACTIVE_TINT = 0.65f
    const val STATUS_BAR_CHIP_TINT = 0.70f
    private const val LOCKSCREEN_SHORTCUT_TINT = 0.30f

    /** A row as wide as the glass panel it sits on stays clear (Glass Layer's PANEL_LAYER). */
    private const val PANEL_LAYER = 0.97f

    private const val LIVE = GlassSurface.LIVE

    /** A text toast (SystemUIToast's view): every filled shape in it becomes glass. */
    @JvmStatic
    fun toast(view: View) {
        GlassLayer.attachTree(view, GlassLayer.TOASTS, LIVE) { _, _ -> TOAST_TINT }
    }

    /** A SystemUIDialog, when it starts: the panel and its buttons become glass. */
    @JvmStatic
    fun dialog(dialog: Dialog) {
        val root = dialog.window?.decorView ?: return
        // The power menu is its own surface, with Glass Layer's power menu tints.
        if (dialog.javaClass.name.startsWith("com.android.systemui.globalactions.")) {
            powerMenu(root)
            return
        }
        GlassLayer.attachTree(root, GlassLayer.DIALOGS, LIVE) { view, top ->
            panelTint(view, top, DIALOG_TINT, DIALOG_BUTTON_TINT, DIALOG_ACCENT_TINT,
                DIALOG_ACTIVE_TINT)
        }
    }

    /** The power menu's window (GlobalActionsDialogLite): its panel and buttons. */
    @JvmStatic
    fun powerMenu(root: View) {
        GlassLayer.attachTree(root, GlassLayer.POWER_MENU, LIVE) { view, top ->
            panelTint(view, top, POWER_MENU_TINT, POWER_MENU_BUTTON_TINT, POWER_MENU_ACCENT_TINT,
                POWER_MENU_ACCENT_TINT)
        }
    }

    /** The stock volume dialog (VolumeDialogViewBinder): its panel, ringer and buttons. */
    @JvmStatic
    fun volume(root: View) {
        GlassLayer.attachTree(root, GlassLayer.VOLUME, LIVE) { view, _ ->
            when {
                view is ViewGroup -> VOLUME_TINT
                view.isSelected || view.isActivated -> VOLUME_ACTIVE_TINT
                else -> VOLUME_BUTTON_TINT
            }
        }
    }

    /** A lockscreen shortcut button (KeyguardQuickAffordanceViewBinder). */
    @JvmStatic
    fun lockscreenShortcut(view: View) {
        if (!GlassLayer.isAttached(view)) {
            GlassLayer.attach(view, GlassLayer.LOCKSCREEN_SHORTCUTS, LOCKSCREEN_SHORTCUT_TINT,
                LIVE or GlassSurface.RIM_ON_TOP)
        }
    }

    // Glass Layer's DialogGlass rules: the window's panel, accent buttons, groups and buttons
    // each get their own tint; a group that covers the glass panel under it stays clear.
    private fun panelTint(
        view: View,
        root: View,
        panel: Float,
        button: Float,
        accent: Float,
        activeGroup: Float,
    ): Float {
        if (view.width <= 0) return Float.NaN
        if (view === root) return panel
        val group = view is ViewGroup
        val isAccent = GlassLayer.isAccent(view)
        if (group && !isAccent && coversGlassParent(view, root)) return 0f
        return when {
            isAccent -> if (group) activeGroup else accent
            group -> panel
            else -> button
        }
    }

    private fun coversGlassParent(view: View, root: View): Boolean {
        var parent = view.parent
        while (parent is View) {
            if (GlassLayer.isAttached(parent)) {
                val width =
                    SolidShapes.of(parent.background)?.bounds?.width()?.takeIf { it > 0 }
                        ?: parent.width
                return width > 0 && view.width >= width * PANEL_LAYER
            }
            if (parent === root) return false
            parent = parent.parent
        }
        return false
    }

    private var chipState: State<Boolean>? = null

    /** Whether the status bar chips are glass, as Compose state. */
    @JvmStatic
    fun statusBarChips(): State<Boolean> =
        chipState
            ?: synchronized(this) {
                chipState
                    ?: mutableStateOf(GlassLayer.isActive(GlassLayer.STATUS_BAR_CHIPS)).also {
                        state ->
                        GlassLayer.addListener {
                            state.value = GlassLayer.isActive(GlassLayer.STATUS_BAR_CHIPS)
                        }
                        chipState = state
                    }
            }

    /** A status bar chip's fill: Glass Layer's chip tint of it while the chips are glass. */
    @JvmStatic
    fun chipColor(color: Color, glass: Boolean): Color =
        if (!glass) color
        else color.copy(alpha = color.alpha * (STATUS_BAR_CHIP_TINT * GlassLayer.tintScale())
            .coerceAtMost(1f))
}

@Composable
fun glassLayerChips(): Boolean = remember { GlassLayerUi.statusBarChips() }.value
