/*
 * SPDX-FileCopyrightText: 2026 DrDisagree, Miku-Picked UI
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Ported from Glass Layer (github.com/Mahmud0808/GlassLayer, GPL-3.0): LiquidGlassShader and
 * LiquidGlassRenderer.drawSource, in Java. Refracts a small RenderNode (what lies under a glass
 * thumb) through a rounded lens with a lit rim, a soft inner shadow and chromatic dispersion.
 */

package com.android.internal.graphics.picked;

import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Path;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;

/**
 * A Liquid Glass lens drawn from a source RenderNode, with no screen capture.
 *
 * @hide
 */
final class GlassLens {

    private static final float REFRACTION_RATIO = 0.18f;
    private static final float MAX_EDGE_PX = 40f;
    private static final float DISPERSION = 0.06f;
    private static final float SATURATION = 1.15f;
    private static final float RIM_WIDTH = 2.5f;

    // Glass Layer's light, from the top left: normalize(-0.5, -0.85).
    private static final float LIGHT_X = -0.507f;
    private static final float LIGHT_Y = -0.862f;

    private static final String SOURCE = ""
            + "uniform shader image;\n"
            + "uniform float2 origin;\n"
            + "uniform float2 size;\n"
            + "uniform float4 radii;\n"
            + "uniform float refraction;\n"
            + "uniform float edge;\n"
            + "uniform float dispersion;\n"
            + "uniform float rim;\n"
            + "uniform float rimWidth;\n"
            + "uniform float reflection;\n"
            + "uniform float shadow;\n"
            + "uniform float saturation;\n"
            + "uniform float2 lightDir;\n"
            + "float corner(float2 a, float r) {\n"
            + "    float2 q = r - a;\n"
            + "    float k = max(min(edge * 0.5, r), 1.0);\n"
            + "    float h = clamp(0.5 + 0.5 * (q.y - q.x) / k, 0.0, 1.0);\n"
            + "    float inside = mix(q.x, q.y, h) + k * h * (1.0 - h);\n"
            + "    return length(max(q, 0.0)) + min(inside, 0.0) - r;\n"
            + "}\n"
            + "float shape(float2 coord) {\n"
            + "    float2 p = coord - origin;\n"
            + "    float2 far = size - p;\n"
            + "    float d = corner(p, radii.x);\n"
            + "    d = max(d, corner(float2(far.x, p.y), radii.y));\n"
            + "    d = max(d, corner(far, radii.z));\n"
            + "    return max(d, corner(float2(p.x, far.y), radii.w));\n"
            + "}\n"
            + "half4 main(float2 coord) {\n"
            + "    float2 e = float2(1.0, 0.0);\n"
            + "    float d = shape(coord);\n"
            + "    float2 normal = float2(shape(coord + e.xy) - shape(coord - e.xy),\n"
            + "                           shape(coord + e.yx) - shape(coord - e.yx));\n"
            + "    normal = normal / max(length(normal), 0.0001);\n"
            + "    float band = clamp(1.0 + d / max(edge, 1.0), 0.0, 1.0);\n"
            + "    float bend = band * band * band;\n"
            + "    float2 offset = -normal * refraction * bend;\n"
            + "    float rimBand = smoothstep(-rimWidth, 0.0, d);\n"
            + "    float light = clamp(dot(normal, lightDir), 0.0, 1.0);\n"
            + "    float shade = clamp(dot(normal, normalize(float2(0.5, 0.85))), 0.0, 1.0);\n"
            + "    half4 color;\n"
            + "    color.r = image.eval(coord + offset * (1.0 + dispersion)).r;\n"
            + "    color.g = image.eval(coord + offset).g;\n"
            + "    color.b = image.eval(coord + offset * (1.0 - dispersion)).b;\n"
            + "    color.a = image.eval(coord + offset).a;\n"
            + "    half luma = dot(color.rgb, half3(0.2126, 0.7152, 0.0722));\n"
            + "    color.rgb = clamp(mix(half3(luma), color.rgb, half(saturation)), 0.0, 1.0);\n"
            + "    half lit = half(clamp(rim * rimBand * (0.18 + 0.5 * light)\n"
            + "            + reflection * bend * 0.12 * light, 0.0, 1.0));\n"
            + "    half dark = half(clamp(shadow * bend * 0.06 * shade, 0.0, 1.0));\n"
            + "    color.rgb += half3(lit);\n"
            + "    color.rgb -= half3(dark * color.a);\n"
            + "    color.a = clamp(max(color.a, lit + dark), 0.0, 1.0);\n"
            + "    color.rgb = clamp(color.rgb, 0.0, color.a);\n"
            + "    if (color.a < 0.02) return half4(0.0);\n"
            + "    return color;\n"
            + "}\n";

    private final RuntimeShader mShader = new RuntimeShader(SOURCE);
    private final RenderNode mNode = new RenderNode("GlassLayerLens");
    private final Matrix mMatrix = new Matrix();
    private final float mRefraction;
    private final float mReflection;
    private final float mRim;
    private final float mShadow;

    GlassLens(float refraction, float reflection, float rim, float shadow) {
        mRefraction = refraction;
        mReflection = reflection;
        mRim = rim;
        mShadow = shadow;
    }

    /**
     * Draws {@code source}, mapped by {@code sourceMatrix}, through a lens of the given size and
     * corner radius at the canvas origin, clipped to {@code clip}.
     */
    void drawSource(Canvas canvas, Path clip, RenderNode source, Matrix sourceMatrix,
            float width, float height, float radius, float alpha) {
        if (!canvas.isHardwareAccelerated() || width <= 0f || height <= 0f || alpha <= 0f) return;
        int pad = (int) Math.ceil(Math.min(width, height) * 0.25f);
        int nodeWidth = (int) Math.ceil(width + pad * 2);
        int nodeHeight = (int) Math.ceil(height + pad * 2);

        mNode.setPosition(0, 0, nodeWidth, nodeHeight);
        RecordingCanvas recording = mNode.beginRecording(nodeWidth, nodeHeight);
        try {
            mMatrix.set(sourceMatrix);
            mMatrix.postTranslate(pad, pad);
            recording.concat(mMatrix);
            recording.drawRenderNode(source);
        } finally {
            mNode.endRecording();
        }

        float base = Math.min(Math.min(width, height) * REFRACTION_RATIO, MAX_EDGE_PX);
        float r = Math.min(radius, Math.min(width, height) / 2f);
        mShader.setFloatUniform("lightDir", LIGHT_X, LIGHT_Y);
        mShader.setFloatUniform("origin", pad, pad);
        mShader.setFloatUniform("size", width, height);
        mShader.setFloatUniform("radii", r, r, r, r);
        mShader.setFloatUniform("refraction", base * mRefraction);
        mShader.setFloatUniform("edge", Math.max(base, 1f));
        mShader.setFloatUniform("dispersion", DISPERSION);
        mShader.setFloatUniform("rim", mRim);
        mShader.setFloatUniform("rimWidth", RIM_WIDTH);
        mShader.setFloatUniform("reflection", mReflection);
        mShader.setFloatUniform("shadow", mShadow);
        mShader.setFloatUniform("saturation", SATURATION);
        mNode.setRenderEffect(RenderEffect.createRuntimeShaderEffect(mShader, "image"));
        mNode.setAlpha(alpha);

        int count = canvas.save();
        canvas.clipPath(clip);
        canvas.translate(-pad, -pad);
        canvas.drawRenderNode(mNode);
        canvas.restoreToCount(count);
    }
}
