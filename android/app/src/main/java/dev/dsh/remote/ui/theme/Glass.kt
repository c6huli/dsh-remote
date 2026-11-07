package dev.dsh.remote.ui.theme

import android.graphics.BlurMaskFilter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

/**
 * 玻璃表面:**背后的背景取样 → 叠半透明填充 → 发丝描边 + 顶边高光 → 外投影**。
 *
 * ```kotlin
 * GlassSurface(tier = GlassTier.Chrome, shape = AppRadius.pill(), onClick = open) {
 *     Text("修复登录重复提交")
 * }
 * ```
 *
 * ## 为什么没有 BackdropFilter
 *
 * Compose 没有 iOS 那种"模糊背后的内容"。这里换个等价的做法:这个 App 的背景
 * 是**同一张渐变 + 两团光**,没有高频细节,所以对背景做模糊几乎什么都不会变。
 * 于是 chrome / sheet 这两层直接在玻璃形状里**重画一遍背景**(按它在窗口里的
 * 位置对齐),再叠半透明填充 —— 背后的滚动内容被"换成了背景",读起来就是一块
 * 磨砂玻璃,而且不用每帧做高斯模糊。
 *
 * ## 圆角必须用真实 density 求路径
 *
 * `Shape.createOutline` 需要一个 [Density] 来把 dp 半径换成 px。早先这里传了
 * `Density(1f)`,在 3x 屏上圆角半径只有实际的 1/3 —— 取样区、发丝边、投影全都
 * 沿一条"过小的圆角"走,于是圆角处出现**透明直边**(取样没盖到角上、描边斜切
 * 过角)。现在一律用 DrawScope 自己的 density(它就是当前屏幕的 density)。
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    tier: GlassTier = GlassTier.Card,
    shape: Shape = AppRadius.forTier(tier),
    padding: PaddingValues? = null,
    style: GlassStyle? = null,
    /** 强制开 / 关背景取样。null = 跟随层级与主题总开关。 */
    sample: Boolean? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val surfaces = LocalDshSurfaces.current
    val s = style ?: surfaces.glass(tier)
    val reduce = !surfaces.blurEnabled
    val wantSample = !reduce && (sample ?: s.blurs)
    val fill = if (wantSample) s.fill else s.fallbackFill

    // 取样要按窗口坐标对齐,否则玻璃里的渐变和页面上的对不上,边缘会露缝。
    var position by remember { mutableStateOf(Offset.Zero) }
    val windowSize = rememberWindowSize()

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale = if (pressed) AppMotion.pressScale else 1f

    val base = modifier
        .onGloballyPositioned { position = it.positionInWindow() }
        // 投影画在最外层(形状之外),必须在 clip 之前。
        .drawBehind { drawOutsideShadows(shape, s.shadows) }
        .clip(shape)
        .then(
            if (wantSample) {
                Modifier.drawBehind {
                    clipPath(shape.toPath(size, this)) {
                        drawBackdrop(
                            surfaces = surfaces,
                            glow = true,
                            origin = position,
                            area = windowSize,
                        )
                    }
                }
            } else {
                Modifier
            },
        )
        .background(fill)
        .drawWithContent {
            drawContent()
            drawGlassEdges(shape, s.hairline, s.hairlineWidth, s.rim)
        }
        .then(
            if (onClick != null || onLongClick != null) {
                Modifier.clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = { onClick?.invoke() },
                )
            } else {
                Modifier
            },
        )
        .then(
            if (onClick != null || onLongClick != null) {
                Modifier.pressScale(pressScale)
            } else {
                Modifier
            },
        )

    // 内容层默认不撑满:卡片是"贴着左上角排版"的,全局居中会改变卡片文字的对齐。
    // 需要居中的固定尺寸玻璃(顶栏圆钮、跳到底部)自己用 fillMaxSize 的内容盒子。
    val withPadding = if (padding != null) base.padding(padding) else base
    Box(withPadding) { content() }
}

/** 窗口尺寸,用于把背景取样对齐到整页。 */
@Composable
private fun rememberWindowSize(): Size {
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    return remember(configuration.screenWidthDp, configuration.screenHeightDp, density) {
        with(density) {
            Size(configuration.screenWidthDp.dp.toPx(), configuration.screenHeightDp.dp.toPx())
        }
    }
}

/** 各层级的默认圆角。 */
fun AppRadius.forTier(tier: GlassTier): Shape = when (tier) {
    GlassTier.Chrome, GlassTier.Sheet -> RoundedCornerShape(AppShape.xl)
    GlassTier.Card -> RoundedCornerShape(AppShape.md)
    GlassTier.Pill -> RoundedCornerShape(percent = 50)
}

/**
 * 形状 → Path。
 *
 * [density] 必须是**真实屏幕 density**:dp 半径靠它换算成 px。传 1f 会让圆角
 * 只有实际的 1/density,于是描边与取样在角上切出直边。
 */
fun Shape.toPath(size: Size, density: Density): Path {
    val outline = createOutline(size, androidx.compose.ui.unit.LayoutDirection.Ltr, density)
    return when (outline) {
        is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
        is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
        is Outline.Generic -> outline.path
    }
}

/**
 * 只在形状**外部**画投影(even-odd:外框减去形状本身)。
 *
 * 用 [BlurMaskFilter] 画真模糊:早先用 `Stroke(width = blurRadius)` 描一条粗带,
 * 边缘是硬的 —— 圆角外面套着一圈直角感的实心边,看起来就是"透明直边"。模糊半径
 * 才是投影该有的样子。
 */
fun DrawScope.drawOutsideShadows(shape: Shape, shadows: List<ShadowSpec>) {
    if (shadows.isEmpty() || size.minDimension <= 0f) return
    val rect = Rect(Offset.Zero, size)
    var extent = 0f
    for (shadow in shadows) {
        val distance = kotlin.math.sqrt(shadow.offsetX * shadow.offsetX + shadow.offsetY * shadow.offsetY)
        extent = maxOf(extent, shadow.blurRadius * 2f + distance)
    }
    val outer = Path().apply {
        addRect(rect.inflate(extent + 2f))
        addPath(shape.toPath(size, this@drawOutsideShadows))
        fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
    }
    clipPath(outer) {
        for (shadow in shadows) {
            val translated = Path().apply {
                addPath(shape.toPath(size, this@drawOutsideShadows), Offset(shadow.offsetX, shadow.offsetY))
            }
            drawIntoCanvas { canvas ->
                val paint = Paint()
                val native = paint.asFrameworkPaint()
                native.isAntiAlias = true
                native.color = shadow.color.toArgb()
                // Blur.NORMAL 两头都虚,正是投影要的形态。
                native.maskFilter = BlurMaskFilter(
                    shadow.blurRadius.coerceAtLeast(0.5f),
                    BlurMaskFilter.Blur.NORMAL,
                )
                canvas.nativeCanvas.drawPath(translated.asAndroidPath(), native)
            }
        }
    }
}

/** 发丝描边 + 顶边高光。描边画在形状内沿,裁剪时不会被切掉一半。 */
fun DrawScope.drawGlassEdges(shape: Shape, hairline: Color, hairlineWidth: Float, rim: Color) {
    if (size.minDimension <= 0f) return
    val width = maxOf(hairlineWidth, 0.5f)
    // 内缩半个线宽,让描边整条落在形状内部;半径也要按同样内缩后的尺寸重算。
    val inset = width / 2f
    val innerSize = Size(size.width - width, size.height - width)
    if (innerSize.width <= 0f || innerSize.height <= 0f) return
    val path = shape.toPath(innerSize, this).apply { translate(Offset(inset, inset)) }
    if (hairline.alpha > 0f) {
        drawPath(path = path, color = hairline, style = Stroke(width = width))
    }
    if (rim.alpha > 0f) {
        drawPath(
            path = path,
            brush = Brush.verticalGradient(
                colors = listOf(rim, rim.copy(alpha = 0f)),
                startY = 0f,
                endY = size.height * 0.45f,
                tileMode = androidx.compose.ui.graphics.TileMode.Clamp,
            ),
            style = Stroke(width = maxOf(width, 0.8f)),
        )
    }
}

/** 一个形状减去另一个形状的补集路径(供外部复用)。 */
internal fun difference(outer: Path, inner: Path): Path = Path().apply {
    op(outer, inner, PathOperation.Difference)
}
