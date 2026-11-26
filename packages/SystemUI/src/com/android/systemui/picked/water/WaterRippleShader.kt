/*
 * SPDX-FileCopyrightText: 2026 DrDisagree, Miku-Picked UI
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * From Glass Layer (github.com/Mahmud0808/GlassLayer, GPL-3.0), glass/WaterRippleShader.kt,
 * unchanged apart from the package: refracts the captured screen through the water surface, with
 * a sky matcap, fresnel and a glint, fading out over the last 30 %.
 */
package com.android.systemui.picked.water

internal object WaterRippleShader {

    const val BACKDROP = "backdrop"
    const val FIELD = "field"
    const val PROGRESS = "progress"
    const val REFRACTION = "refraction"
    const val HAS_BACKDROP = "hasBackdrop"
    const val OVERLAY = "overlay"
    const val RESOLUTION = "resolution"

    val SOURCE = """
        uniform shader backdrop;
        uniform shader field;
        uniform float progress;
        uniform float refraction;
        uniform float hasBackdrop;
        uniform float overlay;
        uniform float2 resolution;

        float2 inside(float2 q) {
            return clamp(q, float2(0.5), resolution - 0.5);
        }

        float3 matcap(float3 n) {
            float up = clamp(-n.y * 0.5 + 0.5, 0.0, 1.0);
            float3 sky = mix(float3(0.16, 0.17, 0.2), float3(0.82, 0.88, 0.96), smoothstep(0.15, 0.95, up));
            float edge = smoothstep(0.55, 0.98, length(n.xy));
            float3 rim = float3(0.98, 0.8, 0.52);
            float side = clamp(n.x * 0.5 + 0.5, 0.0, 1.0);
            float3 ground = mix(float3(0.4, 0.38, 0.32), float3(0.5, 0.45, 0.36), side);
            float3 body = mix(ground, sky, smoothstep(0.3, 0.7, up));
            return mix(body, rim, edge * 0.7);
        }

        half4 main(float2 p) {
            float4 f = float4(field.eval(p));
            float2 g = f.rg;
            float slope = length(g);
            float3 n = normalize(float3(-g * 1.35 * 6.0, 1.0));

            float3 ray = refract(float3(0.0, 0.0, -1.0), n, 1.0 / 1.5);
            float2 offset = ray.xy * refraction;

            float3 capture = float3(0.0);
            float3 bent = float3(0.0);
            if (hasBackdrop > 0.5) {
                capture = float3(backdrop.eval(p).rgb);
                bent = float3(
                    backdrop.eval(inside(p + offset * 1.02)).r,
                    backdrop.eval(inside(p + offset)).g,
                    backdrop.eval(inside(p + offset * 0.98)).b
                );
            }

            float3 light = normalize(float3(-0.42, -0.7, 0.58));
            float shoulder = clamp(dot(n.xy, -light.xy) * 2.5, -1.0, 1.0) * 0.26;
            float3 color = bent * (1.0 + shoulder);

            float fresnel = 0.02 + 0.98 * pow(1.0 - clamp(n.z, 0.0, 1.0), 5.0);
            color = mix(color, mix(bent, matcap(n), 0.55), clamp(fresnel * 0.6, 0.0, 1.0));

            float3 mirrored = reflect(-light, n);
            float glint = pow(max(mirrored.z, 0.0), 420.0);
            color += float3(glint);

            float coverage = smoothstep(0.012, 0.09, slope * 3.0);
            color = clamp(color, 0.0, 1.0);

            float fade = progress < 0.7 ? 1.0 : clamp((1.0 - progress) / 0.3, 0.0, 1.0);
            float3 target = mix(capture, color, coverage);

            if (overlay < 0.5) {
                return half4(half3(mix(capture, target, fade)), 1.0);
            }
            if (hasBackdrop < 0.5) {
                return half4(0.0);
            }

            float alpha = 0.0;
            for (int c = 0; c < 3; c++) {
                float t = target[c];
                float d = capture[c];
                float need = t > d ? (t - d) / max(1.0 - d, 0.0001) : (d - t) / max(d, 0.0001);
                alpha = max(alpha, need);
            }
            alpha = clamp(alpha, 0.0, 1.0);
            float3 premultiplied = clamp(target - capture * (1.0 - alpha), 0.0, 1.0);
            return half4(half3(premultiplied * fade), half(alpha * fade));
        }
    """.trimIndent()
}
