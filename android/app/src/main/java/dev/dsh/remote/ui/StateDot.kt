package dev.dsh.remote.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.dsh.remote.ui.theme.AppMotion
import dev.dsh.remote.ui.theme.LocalDshSemantic
import dev.dsh.remote.ui.theme.rememberReducedMotion

/**
 * DSH 状态点。
 *
 * - `ongoing`:空心圆环,缓慢呼吸(运行中);
 * - `done` / `warning` / `error`:实心小点 + 12% 光晕;
 * - `idle`:不画(与 DSH 一致)。
 *
 * 颜色全部走语义色:状态色的色相是语义,但深浅必须跟主题走。
 */
@Composable
fun StateDot(state: String, size: Dp = 8.dp, modifier: Modifier = Modifier) {
    if (state == "idle") return
    val sem = LocalDshSemantic.current
    val scheme = MaterialTheme.colorScheme
    val color: Color = when (state) {
        "ongoing" -> scheme.primary
        "done" -> sem.success
        "warning" -> sem.warning
        "error" -> sem.danger
        else -> scheme.onSurfaceVariant
    }
    if (state == "ongoing") {
        val ring = (size.value / 5f).coerceAtLeast(1f).dp
        if (rememberReducedMotion()) {
            Box(modifier.size(size).border(ring, color, CircleShape))
            return
        }
        val transition = rememberInfiniteTransition(label = "state-dot-ongoing")
        val alpha by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(AppMotion.breathMs / 3, easing = AppMotion.std),
                RepeatMode.Reverse,
            ),
            label = "state-dot-alpha",
        )
        Box(modifier.size(size).border(ring, color.copy(alpha = alpha), CircleShape))
    } else {
        Box(modifier.size(size)) {
            Box(Modifier.matchParentSize().background(color.copy(alpha = 0.12f), CircleShape))
            Box(
                Modifier
                    .size(size * 0.6f)
                    .align(Alignment.Center)
                    .background(color, CircleShape),
            )
        }
    }
}
