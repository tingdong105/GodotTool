// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package com.winter.godot.tool.ui.components.glass

import androidx.annotation.FloatRange
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import top.yukonga.miuix.kmp.blur.RuntimeShader
import top.yukonga.miuix.kmp.blur.asBrush
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Directional light contributing to an [EdgeStroke].
 *
 * The shader normalizes `(x - 0.5, y - 0.7, z)` into the light direction, so `(0.5, 0.7)` is the
 * reference origin and contributes nothing; a negative [z] places the light behind the surface
 * plane and lights the inward-facing edge.
 *
 * @property color Its alpha scales the contribution.
 * @property intensity Multiplied with [color] alpha.
 * @author compose-miuix-ui contributors
 * @date 2026-11-25
 */
@Immutable
data class EdgeLightSource(
    val x: Float,
    val y: Float,
    val z: Float,
    val color: Color = Color.White,
    @param:FloatRange(from = 0.0) val intensity: Float = 1f,
)

/**
 * How each light's rim contribution is gated — the term multiplied into the Lambert term before it
 * is squared, deciding which arc of the silhouette a light can reach.
 * @author compose-miuix-ui contributors
 * @date 2026-11-25
 */
enum class EdgePeak {

    /**

     * Fixed vertical gate: the primary owns the upper half of the rim, the secondary the lower.

     */
    Vertical,

    /**

     * Gate follows each light's own XY direction, so the lit arc moves with the light.

     */
    Axial,

    /**

     * `dot(n.xy, L.xy)²`: one light produces two peaks 180° apart.

     */
    Dual,
}

/**
 * Configures the lit edge drawn along a shape's silhouette: a flat stroke band plus a hemispheric
 * rim normal lit by two directional [EdgeLightSource]s.
 *
 * @property width Width of the flat stroke band hugging the outer edge.
 * @property bevel Inward halo depth, the reach of the rim normal.
 * @property color The flat stroke band; its alpha controls strength.
 * @property primary Light gated to the first arc of the rim; which arc depends on [peak].
 * @property secondary Light gated to the opposite arc of the rim.
 * @property blendMode How the stroke composites onto what is already drawn.
 * @property peak Which arc each light can reach.
 * @author compose-miuix-ui contributors
 * @date 2026-11-25
 */
@Immutable
data class EdgeStroke(
    val width: Dp,
    val bevel: Dp,
    val color: Color,
    val primary: EdgeLightSource,
    val secondary: EdgeLightSource,
    val blendMode: BlendMode = BlendMode.Plus,
    val peak: EdgePeak = EdgePeak.Vertical,
)


/**
 * 绘制胶囊双光源边缘高光。
 * @param shape 胶囊形状。
 * @param stroke 高光配置。
 * @param alpha 整体不透明度。
 */
internal fun DrawScope.drawEdgeStroke(shape: Shape, stroke: EdgeStroke, alpha: Float = 1f) {
    if (shape !is CornerBasedShape) return
    drawEdgeStrokeShape(stroke, alpha)
}
/**
 * One cache for the material's own shaders: they are few, hold only a compiled program, and do not
 * depend on any composition.
 */
internal val sharedShaderCache: MutableMap<String, RuntimeShader> = mutableMapOf()

/**

 * Reference origin (in normalized UV) used to convert an [EdgeLightSource] into a 3D direction.

 */
internal const val EDGE_LIGHT_REF_X = 0.5f
internal const val EDGE_LIGHT_REF_Y = 0.7f

/**
 * 绘制胶囊边缘的光照与描边。
 */
internal fun DrawScope.drawEdgeStrokeShape(
    stroke: EdgeStroke,
    alpha: Float,
) {
    if (stroke.width.value <= 0f || alpha <= 0f) return
    if (!isRuntimeShaderSupported()) return
    val widthPx = size.width
    val heightPx = size.height
    if (widthPx <= 0f || heightPx <= 0f) return

    val shader = sharedShaderCache.getOrPut(edgeStrokeShaderKey(stroke.peak)) { RuntimeShader(edgeStrokeShaderSource(stroke.peak)) }
    val radius = size.minDimension / 2f
    shader.setFloatUniform("in_size", size.width, size.height)
    shader.setFloatUniform("in_radii", radius, radius, radius, radius)
    shader.setEdgeStrokeUniforms(
        widthPx = widthPx,
        heightPx = heightPx,
        stroke = stroke,
        alpha = alpha,
        density = this,
    )

    drawRect(brush = shader.asBrush(), blendMode = stroke.blendMode)
}

/**
 * 设置描边宽度、颜色与双光源参数。
 */
internal fun RuntimeShader.setEdgeStrokeUniforms(
    widthPx: Float,
    heightPx: Float,
    stroke: EdgeStroke,
    alpha: Float,
    density: Density,
) {
    val maxRadius = (widthPx * 0.5f).coerceAtMost(heightPx * 0.5f)
    setFloatUniform("in_halfViewFloor", floor(widthPx * 0.5f), floor(heightPx * 0.5f))
    val band = with(density) { stroke.width.toPx() }
    val bevel = with(density) { stroke.bevel.toPx() }
    // Preserve the half-pixel stroke-width bias without rounding to an integer.
    setFloatUniform(
        "in_strokeBand",
        (band + 0.5f).coerceAtMost(maxRadius),
        bevel + 0.5f,
    )
    val color = stroke.color
    setFloatUniform("in_strokeColor", color.red, color.green, color.blue, color.alpha)
    setFloatUniform("in_strokeAlpha", alpha)
    setEdgeLightUniform("in_light1", "in_light1Color", stroke.primary)
    setEdgeLightUniform("in_light2", "in_light2Color", stroke.secondary)
    when (stroke.peak) {
        EdgePeak.Vertical, EdgePeak.Dual -> Unit

        EdgePeak.Axial -> {
            setEdgeAxisUniform("axis1", stroke.primary, -1f)
            setEdgeAxisUniform("axis2", stroke.secondary, 1f)
        }
    }
}

/**

 * Converts a light's UV position into a unit direction and folds its intensity into `w`.

 */
internal fun RuntimeShader.setEdgeLightUniform(
    directionName: String,
    colorName: String,
    light: EdgeLightSource,
) {
    val dx = light.x - EDGE_LIGHT_REF_X
    val dy = light.y - EDGE_LIGHT_REF_Y
    val dz = light.z
    val length = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-6f)
    setFloatUniform(
        directionName,
        dx / length,
        dy / length,
        dz / length,
        light.color.alpha * light.intensity,
    )
    setFloatUniform(colorName, light.color.red, light.color.green, light.color.blue, 0f)
}

/**

 * Sets the projected XY gate of an axial light, falling back to a vertical axis.

 */
internal fun RuntimeShader.setEdgeAxisUniform(name: String, light: EdgeLightSource, fallbackY: Float) {
    val dx = light.x - EDGE_LIGHT_REF_X
    val dy = light.y - EDGE_LIGHT_REF_Y
    val length = sqrt(dx * dx + dy * dy)
    if (length > 1e-3f) {
        setFloatUniform(name, dx / length, dy / length)
    } else {
        // The light points purely along Z: fall back to the canonical upper / lower split.
        setFloatUniform(name, 0f, fallbackY)
    }
}

/**

 * 胶囊距离场，与上游圆角分支一致。

 */
private const val PILL_SILHOUETTE = """
uniform float2 in_size;
uniform float4 in_radii;
float pickRadius(float2 local, float2 b) { return min(in_radii.x, min(b.x, b.y)); }
float silhouetteSdf(float2 local) {
    float2 b = in_size * 0.5;
    float r = min(in_radii.x, min(b.x, b.y));
    float2 d = abs(local - b) - b + r;
    return min(max(d.x, d.y), 0.0) + length(max(d, float2(0.0))) - r;
}
"""
/**
 * Cache key of the compiled edge-stroke shader for [peak].
 */
internal fun edgeStrokeShaderKey(peak: EdgePeak): String = "MiuixEdgeStroke" + peak.name

/**

 * AGSL source of the edge-stroke shader for [peak].

 */
internal fun edgeStrokeShaderSource(peak: EdgePeak): String = buildEdgeStrokeShader(peak)

/**

 * Bevel normal along the edge stroke, as AGSL source.

 */
internal fun edgeStrokeNormalSource(): String = """
float3 rimNormal(float2 coord, float2 half2d, float sdf, float radius, float inner) {
    float2 offset = coord - in_halfViewFloor;
    float2 folded = abs(offset);
    // Lift the point off the surface. Depth is measured in the rim band, height in pixels.
    float depth = smoothstep(-inner, 0.0, sdf);
    float3 point = float3(folded, -sqrt(max(inner * inner - depth * depth, 0.0)));

    float bevel = max(radius, inner);
    float2 reference = half2d - bevel;
    reference.x = min(reference.x, folded.x);
    reference.y = min(reference.y, folded.y);
    float2 toPoint = point.xy - reference;
    float len = length(toPoint);
    reference += ((len > 0.0001) ? toPoint / len : float2(0.0, 1.0)) * (bevel - inner);

    // Inside the reference the surface is flat and faces straight away from the lights.
    if (folded.x < reference.x || folded.y < reference.y) return float3(0.0, 0.0, -1.0);

    float3 normal = normalize(point - float3(reference, 0.0));
    normal.xy *= sign(offset);
    return normal;
}
"""

/**

 * Directional lights of the edge stroke, as AGSL source.

 */
internal fun edgeStrokeLightsSource(peak: EdgePeak): String {
    val lightBlock = when (peak) {
        EdgePeak.Vertical ->
            """
    float lit1 = clamp(dot(normal, in_light1.xyz) * (-normal.y), 0.0, 1.0);
    rgb += in_light1Color.rgb * (lit1 * lit1 * in_light1.w);
    float lit2 = clamp(dot(normal, in_light2.xyz) * normal.y, 0.0, 1.0);
    rgb += in_light2Color.rgb * (lit2 * lit2 * in_light2.w);
"""

        EdgePeak.Axial ->
            """
    float gate1 = max(dot(float3(axis1, 0.0), normal), 0.0);
    float lit1 = clamp(dot(normal, in_light1.xyz) * gate1, 0.0, 1.0);
    rgb += in_light1Color.rgb * (lit1 * lit1 * in_light1.w);
    float gate2 = max(dot(float3(axis2, 0.0), normal), 0.0);
    float lit2 = clamp(dot(normal, in_light2.xyz) * gate2, 0.0, 1.0);
    rgb += in_light2Color.rgb * (lit2 * lit2 * in_light2.w);
"""

        EdgePeak.Dual ->
            """
    float peak1 = dot(normal.xy, in_light1.xy);
    rgb += in_light1Color.rgb * (peak1 * peak1 * in_light1.w);
    float peak2 = dot(normal.xy, in_light2.xy);
    rgb += in_light2Color.rgb * (peak2 * peak2 * in_light2.w);
"""
    }
    return """
float3 edgeStrokeLights(float3 normal) {
    float3 rgb = float3(0.0);
$lightBlock
    return rgb;
}
"""
}

/**
 * 构建胶囊距离场、边缘法线与双光源 shader。
 *
 * Light directions are pre-normalized on the CPU side and `in_light*.w` carries the intensity; under
 * [EdgePeak.Axial] the gate is signed and the *product* is clamped, not either dot separately.
 *
 * Output is premultiplied: the alpha carries the light's own magnitude, so additive channels are
 * never clamped away by a lower alpha.
 */
internal fun buildEdgeStrokeShader(peak: EdgePeak): String {
    val axisUniforms = if (peak == EdgePeak.Axial) {
        """
uniform float2 axis1;
uniform float2 axis2;
"""
    } else {
        ""
    }
    return """
${PILL_SILHOUETTE}
uniform float2 in_halfViewFloor; // half size, floored, so the fold lands on a pixel boundary
uniform float2 in_strokeBand;    // stroke width, bevel
uniform float4 in_strokeColor;   // stroke rgb, stroke opacity
uniform float in_strokeAlpha;    // surface opacity
uniform float4 in_light1;        // direction xyz, intensity
uniform float4 in_light1Color;   // colour rgb, unused
uniform float4 in_light2;        // direction xyz, intensity
uniform float4 in_light2Color;   // colour rgb, unused
$axisUniforms
${edgeStrokeNormalSource()}
${edgeStrokeLightsSource(peak)}
half4 main(float2 coord) {
    float2 half2d = in_size * 0.5;
    float2 folded = abs(coord - half2d);
    float radius = pickRadius(coord, half2d);
    float inner = max(in_strokeBand.y, 0.5);
    float bevel = max(radius, inner);

    // The interior has no rim to light, and it is most of the surface.
    if (folded.x < half2d.x - bevel && folded.y < half2d.y - bevel) return half4(0.0);

    float sdf = silhouetteSdf(coord);
    // A near-step band hugging the outer edge, squared by compositing it twice.
    float width = max(in_strokeBand.x, 0.75);
    float band = smoothstep(-width, -width + 1.0, sdf);
    float3 rgb = in_strokeColor.rgb * (in_strokeColor.a * band * band);
    float3 normal = rimNormal(coord, half2d, sdf, radius, inner);
    rgb += edgeStrokeLights(normal);
    // Light only: alpha stays at zero so a transparent surface never gains opacity from the rim.
    float coverage = clamp(0.5 - sdf, 0.0, 1.0);
    float3 light = rgb * (in_strokeAlpha * coverage);
    // Premultiplied, so the alpha has to match the light: one whose channels sit above its own
    // alpha is clamped away.
    return half4(half3(light), half(clamp(max(max(light.r, light.g), light.b), 0.0, 1.0)));
}
"""
}
