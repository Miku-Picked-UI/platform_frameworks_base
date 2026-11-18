/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.penguin.systemui.light

import android.graphics.RuntimeShader

object GlassRim {
    fun create(): RuntimeShader = RuntimeShader(SOURCE)

    fun update(
        shader: RuntimeShader,
        width: Float,
        height: Float,
        radius: Float,
        active: Boolean,
        dark: Boolean,
    ) {
        val look = if (active) Active else if (dark) InactiveDark else Inactive
        shader.setFloatUniform("uSize", width, height)
        shader.setFloatUniform("uRadius", radius)
        shader.setFloatUniform("uLevel", look.level)
        shader.setFloatUniform("uBands", look.bands)
        shader.setFloatUniform("uSpread", look.spread)
        shader.setFloatUniform("uAlpha", look.nearAlpha, look.farAlpha)
        shader.setFloatUniform("uTurn", lightPoint(width, height, radius))
        shader.setFloatUniform("uGlow", look.glow, look.glowDrop * SCALE, GLOW_FADE * SCALE)
    }

    private fun lightPoint(width: Float, height: Float, radius: Float): Float {
        if (width <= 0f || height <= 0f) return 0.5f
        val shorter = minOf(width, height)
        val corner = minOf(radius, shorter / 2f)
        val side = (width * height - shorter * shorter) / (2f * shorter)
        var position = if (width >= height) side + height / 2f else height / 2f - side
        if (!(width == height && radius >= height / 2f)) position -= corner / 2f
        return position * 0.5f / (width + height) + 0.5f
    }

    private class Look(
        val level: Float,
        nearFull: Float,
        nearFade: Float,
        farFull: Float,
        farFade: Float,
        val nearAlpha: Float,
        val farAlpha: Float,
        nearSpread: Float,
        nearSpreadFade: Float,
        farSpread: Float,
        farSpreadFade: Float,
        val glow: Float,
        val glowDrop: Float,
    ) {
        private val near = maxOf(nearFull, nearFade - nearFull) * SCALE
        private val far = maxOf(farFull, farFade - farFull) * SCALE
        val bands = floatArrayOf(near, near * 2f, far, far * 2f)
        val spread =
            floatArrayOf(
                nearSpread,
                nearSpread + nearSpreadFade,
                farSpread,
                farSpread + farSpreadFade,
            )
    }

    private const val SCALE = 0.875f
    private const val GLOW_FADE = 40f

    private val Inactive =
        Look(0.6f, 2f, 9f, 2f, 6f, 0.25f, 0.6f, 0.25f, 0.35f, 0.15f, 0.35f, 0.15f, 36f)
    private val InactiveDark =
        Look(0.32f, 2f, 9f, 2f, 6f, 0.35f, 0.4f, 0.25f, 0.35f, 0.15f, 0.35f, 0.07f, 25f)
    private val Active =
        Look(1f, 1f, 9f, 2f, 3f, 0.5f, 0.5f, 0.3f, 0.3f, 0.3f, 0.3f, 0.15f, 36f)

    private val SOURCE =
        """
        uniform float2 uSize;
        uniform float uRadius;
        uniform float uLevel;
        uniform float4 uBands;
        uniform float4 uSpread;
        uniform float2 uAlpha;
        uniform float uTurn;
        uniform float3 uGlow;

        ${LightShaders.ROUNDED_BOX}
        ${LightShaders.PERIMETER_LIGHT}
        ${LightShaders.SOFT_LIGHT}

        float grain(float2 p) {
            return fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453);
        }

        float innerGlow(float2 xy, float2 pos, float2 extent, float radius, float d) {
            float2 glowExtent = extent * 1.3;
            float corner = min(min(glowExtent.x, glowExtent.y), max(uGlow.z * 1.2, radius));
            float toGlow = roundedBox(pos - float2(0.0, uGlow.y), glowExtent, corner);
            float glow = smoothstep(-uGlow.z, 0.0, toGlow) * smoothstep(-uGlow.z, 0.0, d);
            glow = mix(glow, pow(glow, 20.0), 0.5) * uGlow.x;
            float n = clamp((grain(xy * 0.013) - 0.5) * 0.008 + 0.5, 0.0, 1.0);
            return clamp(softLight(n, glow), 0.0, 1.0);
        }

        half4 main(float2 xy) {
            float2 extent = uSize * 0.5;
            float2 pos = xy - extent;
            float radius = min(uRadius, min(extent.x, extent.y));
            float d = roundedBox(pos, extent, radius);
            if (d > 0.5) {
                return half4(0.0);
            }
            float glow = innerGlow(xy, pos, extent, radius, d);
            if (d < -max(uBands.y, uBands.w)) {
                return half4(half3(glow), glow);
            }
            float2 light = perimeterLight(pos, extent, d, uTurn, uBands, uSpread, 10.0, 0.5);
            float a = clamp(uAlpha.x * light.x + uAlpha.y * light.y, 0.0, 1.0);
            return half4(half3(uLevel * a + glow), clamp(a + glow, 0.0, 1.0));
        }
        """
            .trimIndent()
}
