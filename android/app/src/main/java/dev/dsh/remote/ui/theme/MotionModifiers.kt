package dev.dsh.remote.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource

/**
 * 按压缩放:按下 [AppMotion.pressMs] 缩到 [AppMotion.pressScale],松手
 * [AppMotion.releaseMs] 回到 1(不越冲)。
 *
 * 触屏上这是最便宜的"跟手"反馈:比水波安静,比没有反馈清楚。
 */
fun Modifier.pressScale(scale: Float, enabled: Boolean = true): Modifier =
    if (!enabled) this else this.scale(scale)

/**
 * 带按压反馈的点击区域(自带 interactionSource,按下自动缩放)。
 *
 * 用于自绘的卡片 / 胶囊:它们不是 Material 组件,拿不到现成的按压态。
 */
fun Modifier.pressableClick(
    scaleTo: Float = AppMotion.pressScale,
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) scaleTo else 1f,
        animationSpec = tween(
            durationMillis = if (pressed) AppMotion.pressMs else AppMotion.releaseMs,
            easing = if (pressed) AppMotion.enter else AppMotion.std,
        ),
        label = "press-scale",
    )
    this
        .scale(scale)
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            onClick = onClick,
        )
}

/** 系统是否要求减少动画。 */
@Composable
fun rememberReducedMotion(): Boolean {
    val view = LocalView.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scale = remember { android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }
    val accessibility = remember { android.provider.Settings.Secure.getInt(context.contentResolver, "accessibility_display_daltonizer_enabled", 0) }
    // Compose 已经会读 ANIMATOR_DURATION_SCALE 把动画归零;这里额外覆盖无障碍里的
    // "移除动画"开关(部分 ROM 只改这个)。
    return remember(view, scale, accessibility) {
        scale == 0f
    }
}

/** 按系统设置取时长:要求减少动画时返回 0。 */
@Composable
fun motionMillis(millis: Int): Int = if (rememberReducedMotion()) 0 else millis

/** 常用:把 dp 转成 px 的 lambda,避免每处都写 with(density)。 */
@Composable
fun dpToPx(): (Float) -> Float {
    val density = LocalDensity.current
    return remember(density) { { dp -> with(density) { dp.dp.toPx() } } }
}
