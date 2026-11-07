package dev.dsh.remote.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * DeepSeek Harness 语义色(会话内容用到的业务语义,与 dsh-client-ui-theme 对齐)。
 *
 * 与 [DshSemanticColors] 的区别:这里是**会话内容**的语义(输入/模型/工具 lane、
 * 上下文占比),那边是**界面状态**的语义(成功/警告/错误/代码井)。
 */
@Immutable
data class DshColors(
    val primary: Color,
    val success: Color,
    val warn: Color,
    val danger: Color,
    val userText: Color,
    val bubble: Color,
    val bubbleHighlight: Color,
    val laneInput: Color,
    val laneModel: Color,
    val laneTool: Color,
    val contextSystem: Color,
    val contextTools: Color,
    val contextMessages: Color,
)

val DshDarkColors = DshColors(
    primary = Color(0xFF679EFE),
    success = Color(0xFF22C55E),
    warn = Color(0xFFF59E0B),
    danger = Color(0xFFF25A5A),
    userText = Color(0xFF93C5FD),
    bubble = Color(0xFF2C2C2E),
    bubbleHighlight = Color(0xFF43454A),
    laneInput = Color(0xFF679EFE),
    laneModel = Color(0xFF9474BC),
    laneTool = Color(0xFFDD8629),
    contextSystem = Color(0xFFADB2B8),
    contextTools = Color(0xFFA78BFA),
    contextMessages = Color(0xFF5686FE),
)

val DshLightColors = DshColors(
    primary = Color(0xFF4176E6),
    success = Color(0xFF22C55E),
    warn = Color(0xFFF59E0B),
    danger = Color(0xFFEC1313),
    userText = Color(0xFF2563EB),
    bubble = Color(0xFFEDF3FE),
    bubbleHighlight = Color(0xFFD3E2FF),
    laneInput = Color(0xFF4176E6),
    laneModel = Color(0xFF7C5CD6),
    laneTool = Color(0xFFDD8629),
    contextSystem = Color(0xFF81858C),
    contextTools = Color(0xFFA78BFA),
    contextMessages = Color(0xFF5686FE),
)

val LocalDshColors = staticCompositionLocalOf { DshDarkColors }

/**
 * 应用主题。
 *
 * [accentId] 选配色(mist / daiqing / dusk / turquoise / amber / graphite),
 * [accentOverride] 给自定义色用;[glassBlur] 是"允许透明玻璃"总开关。
 *
 * 三条铁律:
 *
 * 1. **层次靠材质,不靠阴影。** 页面是渐变,浮在内容上的是玻璃,躺在页面上的
 *    是半透明白;elevation 一律 0,surfaceTint 透明。唯一的投影在玻璃外沿且很淡。
 * 2. **细线是骨架。** 卡片、Chip、输入框、对话框都有 0.5–1dp 的线;它比阴影
 *    安静,在浅色雾蓝底上又比纯色块清楚。
 * 3. **连续圆角。** 全部走 [AppShape] 的五档 + 胶囊,不手写半径。
 */
@Composable
fun DshTheme(
    preference: String = "system",
    accentId: String = AppAccent.mist.id,
    accentOverride: Color? = null,
    glassBlur: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = when (preference) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val accent = if (accentOverride != null) AppAccent.derive(accentOverride) else AppAccent.preset(accentId)
    val surfaces = if (dark) DshSurfaces.dark(accent, glassBlur) else DshSurfaces.light(accent, glassBlur)
    val semantic = DshSemanticColors.forAccent(dark, accent)
    val scheme = dshColorScheme(accent, dark)
    val legacy = if (dark) DshDarkColors else DshLightColors

    CompositionLocalProvider(
        LocalDshColors provides legacy,
        LocalDshSurfaces provides surfaces,
        LocalDshSemantic provides semantic,
        LocalAppAccent provides accent,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = AppType.textTheme,
            shapes = dshShapes(),
        ) {
            // 背景由 AppBackdrop 画(渐变 + 两团光),Surface 只负责让内容有个
            // 透明的承载层 —— 不透明底色会把渐变盖掉。
            Surface(modifier = Modifier.fillMaxSize(), color = Color.Transparent) {
                AppBackdrop(surfaces = surfaces) {
                    content()
                }
            }
        }
    }
}

/** Material 形状:五档连续圆角。 */
fun dshShapes() = androidx.compose.material3.Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(AppShape.xs),
    small = androidx.compose.foundation.shape.RoundedCornerShape(AppShape.sm),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(AppShape.md),
    large = androidx.compose.foundation.shape.RoundedCornerShape(AppShape.lg),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(AppShape.xl),
)

// ---- 主题感知取色(旧名字保留,调用点不用改)----
val DshGreen: Color
    @Composable get() = LocalDshColors.current.success
val DshAmber: Color
    @Composable get() = LocalDshColors.current.warn
val DshRed: Color
    @Composable get() = LocalDshColors.current.danger
val DshPrimary: Color
    @Composable get() = LocalDshColors.current.primary
val DshUserText: Color
    @Composable get() = LocalDshColors.current.userText
val DshLaneInput: Color
    @Composable get() = LocalDshColors.current.laneInput
val DshLaneModel: Color
    @Composable get() = LocalDshColors.current.laneModel
val DshLaneTool: Color
    @Composable get() = LocalDshColors.current.laneTool
val DshContextSystem: Color
    @Composable get() = LocalDshColors.current.contextSystem
val DshContextTools: Color
    @Composable get() = LocalDshColors.current.contextTools
val DshContextMessages: Color
    @Composable get() = LocalDshColors.current.contextMessages
