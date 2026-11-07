package dev.dsh.remote.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 全屏页面背景:**竖向雾蓝渐变 + 两团极淡的光**。
 *
 * - 底层:`backdropTop → backdropBottom` 竖向渐变(浅色彩度 ≤ 0.019)。
 * - 右上:主色光晕(跟随 accent)。左下:暮紫光晕。透明度都在 0.24–0.42,
 *   画在大半径径向渐变里,肉眼只觉得"有点透亮",看不出是两个圆。
 * - 光晕的作用是给玻璃一点可"透"的颜色 —— 纯平背景上的玻璃看起来只是白块。
 *
 * **同一主题内所有页共用同一张背景**,切页时背景不动,只有前景内容在变 ——
 * 这是切换顺滑的关键。
 */
@Composable
fun AppBackdrop(
    modifier: Modifier = Modifier,
    surfaces: DshSurfaces = LocalDshSurfaces.current,
    glow: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            drawBackdrop(surfaces, glow)
        }
        content()
    }
}

/** 把背景画到任意 DrawScope(供 BackdropVeil 之类复用同一套画法)。 */
fun DrawScope.drawBackdrop(
    surfaces: DshSurfaces,
    glow: Boolean = true,
    origin: Offset = Offset.Zero,
    area: Size? = null,
) {
    val size = area ?: size
    val topLeft = Offset(-origin.x, -origin.y)
    // 竖向渐变。
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(surfaces.backdropTop, surfaces.backdropBottom),
            startY = topLeft.y,
            endY = topLeft.y + size.height,
        ),
        topLeft = topLeft,
        size = size,
    )
    if (!glow) return
    val longest = maxOf(size.width, size.height)
    // 右上主色光晕。
    glowAt(
        center = Offset(topLeft.x + size.width * 0.92f, topLeft.y),
        radius = longest * 0.62f,
        color = surfaces.glowPrimary,
    )
    // 左下邻近色光晕。
    glowAt(
        center = Offset(topLeft.x, topLeft.y + size.height),
        radius = longest * 0.55f,
        color = surfaces.glowSecondary,
    )
}

private fun DrawScope.glowAt(center: Offset, radius: Float, color: Color) {
    if (radius <= 0f || color.alpha <= 0f) return
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color, color.copy(alpha = 0f)),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}

/**
 * [AppBackdrop] 的一截:同一张背景,只画底部 [solidHeight] + [feather] 这一条,
 * 底部实心、往上 [feather] 缓缓淡出。
 *
 * 叠在滚动内容之上、浮动控件(输入条、思考胶囊)之下:内容滑到控件后面时淡出,
 * 控件之间的缝里只露背景 —— 底色不跟着滚动内容闪,也不出现割裂的缝。
 */
@Composable
fun BackdropVeilBottom(
    solidHeight: Dp,
    modifier: Modifier = Modifier,
    feather: Dp = 14.dp,
    glow: Boolean = true,
    surfaces: DshSurfaces = LocalDshSurfaces.current,
) {
    Canvas(modifier.fillMaxSize()) {
        val band = (solidHeight + feather).toPx().coerceAtMost(size.height)
        if (band <= 0f) return@Canvas
        val rectTop = size.height - band
        drawVeilBand(
            surfaces = surfaces,
            bandTop = rectTop,
            bandHeight = band,
            featherPx = feather.toPx(),
            fromTop = false,
            glow = glow,
        )
    }
}

/** [AppBackdrop] 的一截:顶部实心,往下 [feather] 淡出(页面顶栏后面)。 */
@Composable
fun BackdropVeilTop(
    height: Dp,
    modifier: Modifier = Modifier,
    feather: Dp = 14.dp,
    glow: Boolean = true,
    surfaces: DshSurfaces = LocalDshSurfaces.current,
) {
    Canvas(modifier.fillMaxSize()) {
        val band = (height + feather).toPx().coerceAtMost(size.height)
        if (band <= 0f) return@Canvas
        drawVeilBand(
            surfaces = surfaces,
            bandTop = 0f,
            bandHeight = band,
            featherPx = feather.toPx(),
            fromTop = true,
            glow = glow,
        )
    }
}

private fun DrawScope.drawVeilBand(
    surfaces: DshSurfaces,
    bandTop: Float,
    bandHeight: Float,
    featherPx: Float,
    fromTop: Boolean,
    glow: Boolean,
) {
    val fade = (featherPx / bandHeight).coerceIn(0f, 1f)
    // 线性斜坡两头都看得出"折痕",用缓动采样。
    val samples = 8
    val stops = ArrayList<Float>(samples + 3)
    val alphas = ArrayList<Float>(samples + 3)
    if (fromTop) {
        stops.add(0f)
        alphas.add(1f)
    }
    for (i in 0..samples) {
        val t = i.toFloat() / samples
        stops.add(if (fromTop) 1f - fade + fade * t else fade * t)
        val eased = t * t * (3f - 2f * t) // smoothstep ≈ easeInOutCubic 的轻量版
        alphas.add(if (fromTop) 1f - eased else eased)
    }
    if (!fromTop) {
        stops.add(1f)
        alphas.add(1f)
    }

    // 把这一条当成一张遮罩:先整幅画背景,再用 alpha 渐变把它裁出来。
    // Compose 没有 saveLayer+dstIn 的直接对应,这里用"只画这一条 + 竖直 alpha
    // 渐变叠一层同色遮罩"达到同样效果:渐变本身是纯色对纯色,视觉等价。
    drawBackdrop(surfaces, glow, origin = Offset(0f, bandTop), area = Size(size.width, size.height))
    // 上/下淡出:盖一层从背景色到透明的渐变,压掉带外的部分。
    val veil = Brush.verticalGradient(
        colorStops = stops.zip(alphas.map { 1f - it }).map { (stop, a) ->
            stop to surfaces.backdropTop.copy(alpha = a.coerceIn(0f, 1f))
        }.toTypedArray(),
        startY = bandTop,
        endY = bandTop + bandHeight,
    )
    drawRect(brush = veil, topLeft = Offset(0f, bandTop), size = Size(size.width, bandHeight))
}

/** 一条发丝线(0.5–1dp)的描边画法,统一线宽。 */
fun DrawScope.hairline(shape: Shape, color: Color, width: Float = 1f) {
    val rect = Size(size.width, size.height)
    val path = when (val outline = shape.createOutline(rect, layoutDirection, this)) {
        is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
        is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
        is Outline.Generic -> outline.path
    }
    drawPath(path = path, color = color, style = Stroke(width = width))
}

/** 供 @Immutable 数据类使用的空阴影占位。 */
@Immutable
internal data object NoShadows
