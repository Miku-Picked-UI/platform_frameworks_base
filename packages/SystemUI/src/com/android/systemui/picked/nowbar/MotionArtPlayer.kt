/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.picked.nowbar

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.util.Log
import android.view.Surface
import android.view.TextureView
import java.io.File
import java.io.IOException

/**
 * [PenguinOS] Loops a motion art video, muted, cropped to fill [view] (PenguinOS'
 * MotionArtVideo without Compose). The view fades in once the first frame is drawn.
 */
internal class MotionArtPlayer(
    private val view: TextureView,
    private val file: File,
    private val onError: () -> Unit,
) : TextureView.SurfaceTextureListener {
    private var player: MediaPlayer? = null
    private var surface: Surface? = null

    init {
        view.isOpaque = false
        view.alpha = 0f
        view.surfaceTextureListener = this
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        release()
        val output = Surface(texture)
        surface = output
        player =
            MediaPlayer().apply {
                setSurface(output)
                isLooping = true
                setVolume(0f, 0f)
                setOnInfoListener { _, what, _ ->
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                        view.animate().alpha(1f).setDuration(400).start()
                    }
                    false
                }
                setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "Playback failed for $file: $what/$extra")
                    onError()
                    true
                }
                setOnPreparedListener { it.start() }
                setOnVideoSizeChangedListener { _, w, h -> cropTo(w, h) }
                try {
                    setDataSource(file.path)
                    prepareAsync()
                } catch (e: IOException) {
                    Log.w(TAG, "Cannot play $file: $e")
                    onError()
                }
            }
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        release()
        return true
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        val player = player ?: return
        cropTo(player.videoWidth, player.videoHeight)
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}

    private fun cropTo(videoWidth: Int, videoHeight: Int) {
        if (videoWidth <= 0 || videoHeight <= 0 || view.width <= 0 || view.height <= 0) return
        val viewWidth = view.width.toFloat()
        val viewHeight = view.height.toFloat()
        val scale = maxOf(viewWidth / videoWidth, viewHeight / videoHeight)
        view.setTransform(
            Matrix().apply {
                setScale(
                    videoWidth * scale / viewWidth,
                    videoHeight * scale / viewHeight,
                    viewWidth / 2f,
                    viewHeight / 2f,
                )
            }
        )
    }

    fun release() {
        player?.release()
        player = null
        surface?.release()
        surface = null
    }

    private companion object {
        const val TAG = "LockscreenMotionArt"
    }
}
