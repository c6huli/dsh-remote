package dev.dsh.remote.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.dsh.remote.ui.icons.DshIcon
import dev.dsh.remote.ui.icons.DshIcons
import dev.dsh.remote.ui.theme.LocalDshSemantic

/**
 * 开机动画（覆盖层）。
 *
 * 它现在盖在主界面上、并会被自动移除，所以必须自己画不透明底色——否则底层
 * 界面会透出来。
 *
 * 刻意只用最基础的绘制：一条转动的弧 + 呼吸的小鱼，纯 Canvas 加两个无限动画，
 * 不解码图片、不做模糊。启动页只负责让"正在启动"可被看见，不该成为新的崩溃面。
 */
@Composable
fun BootSplash(modifier: Modifier = Modifier) {
    val sem = LocalDshSemantic.current
    val accent = MaterialTheme.colorScheme.primary
    val ringTrack = sem.textDisabled.copy(alpha = 0.18f)

    val infinite = rememberInfiniteTransition(label = "boot")
    val breathe by infinite.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breathe",
    )
    val spin by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "spin",
    )
    // 圆心光晕：0.6 → 1 来回，给"正在启动"一点温度。
    val halo by infinite.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "halo",
    )

    // 轻微淡入，避免第一帧生硬跳出。
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(320)) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .alpha(appear.value),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(96.dp)) {
                    val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                    val radius = size.minDimension / 2f - stroke.width
                    val topLeft = Offset(
                        (size.width - radius * 2) / 2f,
                        (size.height - radius * 2) / 2f,
                    )
                    val arcSize = Size(radius * 2, radius * 2)
                    // 圆心光晕：柔和的实心圆，随 halo 呼吸。
                    drawCircle(
                        color = accent.copy(alpha = 0.10f * halo),
                        radius = radius * 0.72f,
                        center = center,
                    )
                    // 底环：极淡，给出圆心
                    drawArc(
                        color = ringTrack,
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = stroke,
                    )
                    // 转动的亮弧：130° 尾巴
                    drawArc(
                        color = accent,
                        startAngle = spin,
                        sweepAngle = 130f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = stroke,
                    )
                }
                DshIcon(
                    DshIcons.Fish,
                    tint = accent,
                    size = 40.dp,
                    contentDescription = null,
                    modifier = Modifier.scale(breathe),
                )
            }

            Spacer(Modifier.height(18.dp))

            Text(
                "DSH Remote",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "探索未至之境",
                style = MaterialTheme.typography.labelSmall,
                color = sem.textTertiary,
            )
        }
    }
}
