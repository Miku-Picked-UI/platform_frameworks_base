/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.volume

import android.animation.LayoutTransition
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.media.AudioManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.app.animation.Interpolators
import com.android.app.tracing.coroutines.launchInTraced
import com.android.app.tracing.coroutines.launchTraced
import com.android.internal.graphics.drawable.BackgroundBlurDrawable
import com.android.systemui.penguin.VolumePanelLook
import com.android.systemui.penguin.VolumePanelLooks
import com.android.systemui.volume.dialog.sliders.ui.CosVolumeSlim
import com.android.systemui.volume.dialog.sliders.ui.VolumePanelSlider
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.onEach
import com.android.systemui.res.R

class StyledVolumePanelLooks @Inject constructor() : VolumePanelLooks {
    override fun current(context: Context): VolumePanelLook? =
        VolumePanelStyle.current(context)
            .takeIf { it != VolumePanelStyle.DEFAULT }
            ?.let { StyledVolumePanelLook(it) }
}

class StyledVolumePanelLook(private val style: VolumePanelStyle) : VolumePanelLook {

    private val isMyUi = style == VolumePanelStyle.MYUI
    private val isCos = style == VolumePanelStyle.COLOR_OS
    private val isColumn = style == VolumePanelStyle.EXPANDABLE || isCos

    override val streams: Set<Int> =
        setOf(
            AudioManager.STREAM_MUSIC,
            AudioManager.STREAM_RING,
            AudioManager.STREAM_ALARM,
            AudioManager.STREAM_VOICE_CALL,
        ) + if (isMyUi) setOf(AudioManager.STREAM_NOTIFICATION) else emptySet()

    override val cardLayout: Int? =
        when (style) {
            VolumePanelStyle.ONE_UI -> R.layout.volume_dialog_oneui
            VolumePanelStyle.MYUI -> R.layout.volume_dialog_myui
            else -> null
        }

    override val floatingSliderLayout: Int = R.layout.volume_dialog_slider

    override val ringerColors: VolumePanelLook.RingerColors? =
        if (isColumn) {
            VolumePanelLook.RingerColors(
                icon = R.color.volume_panel_expandable_icon_on_inactive,
                background = R.color.volume_panel_expandable_button_background,
                selectedIcon = R.color.volume_panel_expandable_icon_on_active,
                selectedBackground = R.color.volume_panel_expandable_track_active,
                hideCard = true,
            )
        } else {
            null
        }

    override fun bindCaptionsButton(button: ImageView) {
        button.setBackgroundResource(
            when (style) {
                VolumePanelStyle.ONE_UI -> R.drawable.volume_panel_oneui_captions_background
                VolumePanelStyle.MYUI -> R.drawable.volume_panel_myui_captions_background
                else -> R.drawable.volume_panel_expandable_captions_background
            }
        )
    }

    override fun captionsIconColor(isEnabled: Boolean): Int =
        when {
            style == VolumePanelStyle.ONE_UI -> R.color.volume_panel_oneui_icon
            isMyUi -> R.color.volume_panel_myui_icon
            isEnabled -> R.color.volume_panel_expandable_icon_on_active
            else -> R.color.volume_panel_expandable_icon_on_inactive
        }

    override fun bindSettingsButton(button: ImageButton): Boolean {
        if (isColumn) {
            button.setBackgroundResource(R.drawable.volume_panel_expandable_button_background)
            button.imageTintList =
                ColorStateList.valueOf(
                    button.context.getColor(R.color.volume_panel_expandable_icon_on_inactive)
                )
        }
        if (isMyUi) {
            button.setImageResource(R.drawable.ic_settings_24dp)
            return true
        }
        return false
    }

    override fun settingsIntent(): Intent? =
        if (cardLayout != null) Intent(Settings.ACTION_SOUND_SETTINGS) else null

    override fun onFloatingSliderAdded(container: View) {
        if (isCos) {
            container.updateLayoutParams<ViewGroup.LayoutParams> {
                width =
                    container.context.resources.getDimensionPixelSize(
                        R.dimen.volume_panel_cos_slider_frame_width
                    )
            }
        }
        if (isMyUi) {
            container.updateLayoutParams<ViewGroup.LayoutParams> {
                width =
                    container.context.resources.getDimensionPixelSize(
                        R.dimen.volume_panel_myui_slider_width
                    )
            }
        }
    }

    @Composable
    override fun Slider(slider: VolumePanelLook.SliderHost) {
        val isExpanded by slider.isExpanded.collectAsStateWithLifecycle()
        VolumePanelSlider(
            viewModel = slider.viewModel,
            overscrollViewModel = slider.overscrollViewModel,
            hapticsViewModelFactory = slider.hapticsViewModelFactory,
            isVolumeDialogVertical = true,
            panelStyle = style,
            showLabel = isExpanded,
        )
    }

    override fun CoroutineScope.bindPanel(view: View, host: VolumePanelLook.PanelHost) {
        if (cardLayout != null) {
            bindCard(view, host)
        } else {
            bindColumn(view, host)
        }
        bindExpandButton(view, host)
    }

    private fun CoroutineScope.bindCard(view: View, host: VolumePanelLook.PanelHost) {
        host.addTouchableBounds(view)
        val cardView: View = if (isMyUi) view.requireViewById(R.id.volume_dialog_myui_card) else view
        val card: Drawable? =
            cardView.background?.let { if (host.showBlur) cardView.frostedCard(it, host) else it }
        if (host.showBlur && card != null) {
            launchTraced("SVPL#cardBlur") {
                host.isBlurSupported.collect { supported ->
                    cardView.applyCardBlurSupport(card, supported)
                }
            }
        }
        if (isMyUi) {
            cardView.background = card
            launchTraced("SVPL#cardCorners") {
                host.isExpanded.collect { isExpanded ->
                    card?.setCornerRadius(
                        cardView.context.resources
                            .getDimensionPixelSize(
                                if (isExpanded) {
                                    R.dimen.volume_panel_myui_background_corner_radius
                                } else {
                                    R.dimen.volume_panel_myui_collapsed_corner_radius
                                }
                            )
                            .toFloat()
                    )
                }
            }
        } else {
            launchTraced("SVPL#cardVisibility") {
                host.isExpanded.collect { isExpanded ->
                    cardView.background = if (isExpanded) card else null
                }
            }
        }
    }

    private fun bindColumn(view: View, host: VolumePanelLook.PanelHost) {
        view.requireViewById<View>(R.id.volume_dialog_background).visibility = View.INVISIBLE
        host.mainSliderContainer.updateLayoutParams<ConstraintLayout.LayoutParams> {
            height =
                view.context.resources.getDimensionPixelSize(
                    if (isCos) {
                        R.dimen.volume_panel_cos_slider_height
                    } else {
                        R.dimen.volume_panel_expandable_slider_height
                    }
                )
            if (isCos) verticalBias = COS_SLIDER_VERTICAL_BIAS
        }
        val floating = host.floatingSlidersContainer
        (floating as? LinearLayout)?.showDividers = LinearLayout.SHOW_DIVIDER_NONE
        (floating.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.topMargin = 0
            it.bottomMargin = 0
            floating.layoutParams = it
        }
        floating.layoutTransition = expandTransition(view)
    }

    private fun CoroutineScope.bindExpandButton(view: View, host: VolumePanelLook.PanelHost) {
        val button =
            view.findViewById<ImageButton>(R.id.volume_dialog_expand)
                ?: addExpandButton(view)
                ?: return
        button.visibility = View.VISIBLE
        if (isCos) {
            button.setBackgroundResource(R.drawable.volume_panel_cos_more_background)
            button.imageTintList = ColorStateList.valueOf(0xE6FFFFFF.toInt())
            button.updateLayoutParams<ViewGroup.LayoutParams> {
                width = view.resources.getDimensionPixelSize(R.dimen.volume_panel_cos_slider_width)
                height = view.resources.getDimensionPixelSize(R.dimen.volume_panel_cos_more_height)
            }
        } else if (style == VolumePanelStyle.EXPANDABLE) {
            button.setBackgroundResource(R.drawable.volume_panel_expandable_button_background)
            button.imageTintList =
                ColorStateList.valueOf(
                    view.context.getColor(R.color.volume_panel_expandable_icon_on_inactive)
                )
            if (isPanelOnLeft(view)) {
                button.rotation = 180f
            }
        }
        host.addTouchableBounds(button)
        if (isCos) {
            val shift =
                view.resources.getDimension(R.dimen.volume_panel_cos_slider_width) *
                    (1f - COS_SLIM_WIDTH) * 0.5f * (if (isPanelOnLeft(view)) -1f else 1f)
            CosVolumeSlim.slim
                .onEach { slim ->
                    button.animate()
                        .scaleX(if (slim) 0f else 1f)
                        .scaleY(if (slim) 0f else 1f)
                        .translationX(if (slim) shift else 0f)
                        .setDuration(if (slim) 450 else 600)
                        .setInterpolator(Interpolators.EMPHASIZED_DECELERATE)
                }
                .launchInTraced("SVPL#cosSlim", this)
        }

        val toggle = View.OnClickListener { host.toggleExpanded() }
        button.setOnClickListener(toggle)

        val collapsedButton: ImageButton? =
            view.findViewById<ImageButton>(R.id.volume_dialog_expand_collapsed)?.also {
                it.setOnClickListener(toggle)
                host.addTouchableBounds(it)
            }

        if (isMyUi) {
            button.visibility = View.GONE
            view.findViewById<View>(R.id.volume_dialog_myui_done)?.setOnClickListener(toggle)
        }

        host.isExpanded
            .onEach { isExpanded ->
                if (isMyUi) {
                    view.applyMyUiChrome(isExpanded)
                    collapsedButton?.visibility = if (isExpanded) View.GONE else View.VISIBLE
                    return@onEach
                }
                if (isCos) {
                    view.applyCosChrome(isExpanded)
                }
                if (style == VolumePanelStyle.ONE_UI) {
                    view.applyOneUiChrome(isExpanded)
                    collapsedButton?.visibility = if (isExpanded) View.GONE else View.VISIBLE
                }
                button.setImageResource(
                    when {
                        style == VolumePanelStyle.ONE_UI -> R.drawable.ic_expand_less_rounded
                        isCos -> R.drawable.ic_volume_panel_cos_more
                        isExpanded -> R.drawable.ic_chevron_right
                        else -> R.drawable.ic_chevron_left
                    }
                )
                button.contentDescription =
                    view.context.getString(
                        if (isExpanded) {
                            R.string.volume_panel_collapse
                        } else {
                            R.string.volume_panel_expand
                        }
                    )
            }
            .launchInTraced("SVPL#isExpanded", this)
    }

    private fun addExpandButton(view: View): ImageButton? {
        val bottomSection =
            view.findViewById<ViewGroup>(R.id.volume_dialog_bottom_section_container)
                ?: return null
        val button =
            LayoutInflater.from(view.context)
                .inflate(R.layout.volume_dialog_expand_button, bottomSection, false) as ImageButton
        bottomSection.addView(button, 0)
        return button
    }

    private fun View.applyCosChrome(isExpanded: Boolean) {
        findViewById<View>(R.id.volume_dialog_top_section_container)?.visibility =
            if (isExpanded) View.VISIBLE else View.GONE
        for (id in intArrayOf(R.id.odi_captions_icon, R.id.volume_dialog_settings)) {
            findViewById<View>(id)?.apply {
                alpha = if (isExpanded) 1f else 0f
                isEnabled = isExpanded
            }
        }
    }

    private fun View.applyOneUiChrome(isExpanded: Boolean) {
        findViewById<View>(R.id.volume_dialog_oneui_header)?.visibility =
            if (isExpanded) View.VISIBLE else View.GONE
        minimumWidth =
            if (isExpanded) {
                context.resources.getDimensionPixelSize(R.dimen.volume_panel_oneui_min_width)
            } else {
                0
            }
    }

    private fun View.applyMyUiChrome(isExpanded: Boolean) {
        val expandedOnly = if (isExpanded) View.VISIBLE else View.GONE
        findViewById<View>(R.id.volume_dialog_myui_title)?.visibility = expandedOnly
        findViewById<View>(R.id.volume_dialog_myui_footer)?.visibility = expandedOnly
        findViewById<View>(R.id.volume_dialog_myui_sound_icon)?.visibility =
            if (isExpanded) View.GONE else View.VISIBLE
        findViewById<View>(R.id.odi_captions_icon)?.apply {
            alpha = if (isExpanded) 0f else 1f
            isEnabled = !isExpanded
        }
        findViewById<ConstraintLayout>(R.id.volume_dialog_myui_card)?.minWidth =
            if (isExpanded) {
                context.resources.getDimensionPixelSize(R.dimen.volume_panel_myui_expanded_width)
            } else {
                0
            }
        findViewById<View>(R.id.volume_dialog_main_slider_container)
            ?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                marginStart =
                    if (isExpanded) {
                        context.resources.getDimensionPixelSize(
                            R.dimen.volume_panel_myui_slider_spacing
                        )
                    } else {
                        0
                    }
            }
    }

    private fun View.frostedCard(card: Drawable, host: VolumePanelLook.PanelHost): Drawable {
        if (card is LayerDrawable) {
            return card
        }
        val blurDrawable = viewRootImpl.createBackgroundBlurDrawable()
        blurDrawable.setCornerRadius(
            context.resources
                .getDimensionPixelSize(
                    if (isMyUi) {
                        R.dimen.volume_panel_myui_background_corner_radius
                    } else {
                        R.dimen.volume_panel_oneui_background_corner_radius
                    }
                )
                .toFloat()
        )
        blurDrawable.setBlurRadius(0)
        return LayerDrawable(arrayOf<Drawable>(blurDrawable, card.mutate())).also {
            applyCardBlurSupport(it, host.isBlurSupported.value)
        }
    }

    /**
     * in.
     */
    private fun expandTransition(view: View): LayoutTransition {
        val shift = view.resources.displayMetrics.density * 32f * (if (isPanelOnLeft(view)) -1 else 1)
        return LayoutTransition().apply {
            setAnimator(
                LayoutTransition.APPEARING,
                ObjectAnimator.ofPropertyValuesHolder(
                    null as Any?,
                    PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 1f),
                    PropertyValuesHolder.ofFloat(View.TRANSLATION_X, shift, 0f),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.85f, 1f),
                ),
            )
            setInterpolator(LayoutTransition.APPEARING, Interpolators.EMPHASIZED_DECELERATE)
            setDuration(LayoutTransition.APPEARING, 300)
            setStartDelay(LayoutTransition.APPEARING, 0)
            disableTransitionType(LayoutTransition.DISAPPEARING)
            disableTransitionType(LayoutTransition.CHANGE_APPEARING)
            disableTransitionType(LayoutTransition.CHANGE_DISAPPEARING)
        }
    }

    private fun Drawable.setCornerRadius(radius: Float) {
        when (this) {
            is LayerDrawable ->
                (0 until numberOfLayers).forEach { getDrawable(it).setCornerRadius(radius) }
            is BackgroundBlurDrawable -> setCornerRadius(radius)
            is GradientDrawable -> cornerRadius = radius
        }
    }

    private fun View.applyCardBlurSupport(card: Drawable, supported: Boolean) {
        val layers = card as? LayerDrawable ?: return
        (layers.getDrawable(0) as BackgroundBlurDrawable).setBlurRadius(
            if (supported) {
                context.resources.getDimensionPixelSize(
                    R.dimen.volume_dialog_background_surface_blur_radius
                )
            } else {
                0
            }
        )
        (layers.getDrawable(1) as GradientDrawable).setColor(
            context.getColor(
                when {
                    isMyUi && supported -> R.color.volume_panel_myui_background_blur
                    isMyUi -> R.color.volume_panel_myui_background_fallback
                    supported -> R.color.volume_panel_oneui_background_blur
                    else -> R.color.volume_panel_oneui_background_fallback
                }
            )
        )
    }

    // Miku-Picked UI: the panel is always on the right here (no "volume panel on the left").
    @Suppress("UNUSED_PARAMETER")
    private fun isPanelOnLeft(view: View): Boolean = false
}

private const val COS_SLIDER_VERTICAL_BIAS = 0.25f
private const val COS_SLIM_WIDTH = 0.16f
