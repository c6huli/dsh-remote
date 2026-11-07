package dev.dsh.remote.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 首次出现:淡入 + 轻微上移,可逐项错开。
 *
 * 列表里第 i 项传 `delayMs = i * AppMotion.staggerMs`,只错开前
 * [AppMotion.staggerLimit] 项 —— 再往后就一起出现,免得用户等。
 */
@Composable
fun FadeInUp(
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    delayMs: Int = 0,
    durationMs: Int = AppMotion.standardMs,
    riseDp: Dp = 8.dp,
    content: @Composable () -> Unit,
) {
    val reduce = rememberReducedMotion()
    val progress = remember { Animatable(if (visible) 0f else 0f) }
    LaunchedEffect(visible, reduce) {
        if (reduce) {
            progress.snapTo(if (visible) 1f else 0f)
            return@LaunchedEffect
        }
        if (visible) {
            if (delayMs > 0) delay(delayMs.toLong())
            progress.animateTo(1f, tween(durationMs, easing = AppMotion.enter))
        } else {
            progress.animateTo(0f, tween(AppMotion.quickMs, easing = AppMotion.exit))
        }
    }
    Box(
        modifier.graphicsLayer {
            alpha = progress.value
            translationY = (1f - progress.value) * riseDp.toPx()
        },
    ) {
        content()
    }
}

/** 呼吸:环境类动画(连接中、进行中)。2.4s 一个周期,不打扰。 */
@Composable
fun rememberBreath(durationMs: Int = AppMotion.breathMs): Float {
    val reduce = rememberReducedMotion()
    if (reduce) return 1f
    val anim = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            anim.animateTo(1f, tween(durationMs / 2, easing = AppMotion.std))
            anim.animateTo(0f, tween(durationMs / 2, easing = AppMotion.std))
        }
    }
    return 0.55f + 0.45f * anim.value
}

/**
 * 骨架屏:浅色块 + 一道缓慢扫过的高光。用于加载中的列表。
 */
@Composable
fun SkeletonBlock(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(AppShape.sm),
    height: Dp = 14.dp,
) {
    val surfaces = LocalDshSurfaces.current
    val sem = LocalDshSemantic.current
    val reduce = rememberReducedMotion()
    val anim = remember { Animatable(0f) }
    LaunchedEffect(reduce) {
        if (reduce) return@LaunchedEffect
        while (true) {
            anim.animateTo(1f, tween(1200, easing = AppMotion.std))
            anim.snapTo(0f)
        }
    }
    val base = sem.textDisabled.copy(alpha = 0.22f)
    Box(
        modifier
            .height(height)
            .clip(shape)
            .background(base)
            .drawBehind {
                if (reduce) return@drawBehind
                val x = size.width * (anim.value * 2f - 0.5f)
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            surfaces.card.rim.copy(alpha = 0.55f),
                            Color.Transparent,
                        ),
                        startX = x - size.width * 0.35f,
                        endX = x + size.width * 0.35f,
                    ),
                )
            },
    )
}

/**
 * 状态点:静止时一个实心圆,运行中带呼吸光晕。比转圈图标安静。
 */
@Composable
fun BreathingDot(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 8.dp,
    running: Boolean = false,
) {
    val breath = if (running) rememberBreath() else 1f
    Box(modifier.size(size * 2.4f), contentAlignment = Alignment.Center) {
        if (running) {
            Box(
                Modifier
                    .size(size * 2.2f)
                    .alpha((breath - 0.55f) * 0.6f)
                    .background(color, CircleShape),
            )
        }
        Box(Modifier.size(size).background(color, CircleShape))
    }
}

/**
 * 分区小标题:小字号 + 字距,代替粗分割线。
 */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color? = null) {
    val sem = LocalDshSemantic.current
    Text(
        text = text,
        modifier = modifier,
        style = AppType.eyebrow(color ?: sem.textTertiary),
    )
}

/**
 * 一行统计 / 键值对:左边说明,右边数值(等宽,数字不跳动)。
 */
@Composable
fun StatRow(label: String, value: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val sem = LocalDshSemantic.current
    Row(
        modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        Text(value, style = AppType.monoSmall(color = sem.textTertiary))
    }
}

/** 竖向间距的小工具(比 Spacer(Modifier.height(x)) 短)。 */
@Composable
fun VSpace(dp: Dp) {
    Spacer(Modifier.height(dp))
}

/** 横向间距。 */
@Composable
fun HSpace(dp: Dp) {
    Spacer(Modifier.width(dp))
}
