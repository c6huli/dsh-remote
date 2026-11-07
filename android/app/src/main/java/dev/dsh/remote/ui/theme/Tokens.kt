package dev.dsh.remote.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 字体规范:**系统无衬线负责一切文字,等宽只给代码**。
 *
 * 不打包中文字体的理由:思源黑体单字重 10–20MB,APK 已经背着 Shizuku 等依赖;
 * 而且国产 ROM(MIUI/HyperOS、ColorOS、OriginOS、HarmonyOS)把系统字体换成了
 * MiSans、OPPO Sans、vivo Sans、HarmonyOS Sans,质量都很好,跟着系统走最协调。
 *
 * 字重只用两档:400 / 600。AOSP 只带 Noto Sans CJK Regular 一个字重,w500 会被
 * 就近映射回 Regular,w600 才走合成加粗。所以层级只靠字号 + 颜色,不要指望 500
 * 和 400 在中文上看得出区别。
 *
 * 等宽用系统 `monospace`(代码是次要内容,不值得为它再塞 0.8MB)。
 */
object AppType {

    /** 无衬线族。null = 系统默认。 */
    val sansFamily: FontFamily? = null

    /** 等宽:系统 monospace,中文回落到系统黑体。 */
    val monoFamily: FontFamily = FontFamily.Monospace

    /**
     * 等宽样式统一出口 —— 业务代码禁止再写字面量 fontFamily。
     *
     * 注意这里用 [build] 而不是 `: TextStyle = TextStyle(...)` 直接构造:在 Kotlin 里
     * 返回类型位置上的 `TextStyle` 会被解析成 `SpanStyle + ParagraphStyle` 那个重载,
     * 于是 fontSize/color 这些命名参数全部报"找不到参数"。
     */
    fun mono(
        size: Int = 13,
        weight: FontWeight? = null,
        color: Color? = null,
        lineHeight: Int = 20,
    ): TextStyle = build(
        fontFamily = monoFamily,
        fontSize = size.sp,
        fontWeight = weight,
        color = color,
        lineHeight = lineHeight.sp,
    )

    /** TextStyle 的唯一构造入口(绕开上面那个重载解析问题)。 */
    fun build(
        fontFamily: FontFamily? = null,
        fontSize: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
        fontWeight: FontWeight? = null,
        color: Color? = null,
        lineHeight: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
        letterSpacing: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
    ): TextStyle {
        var style = TextStyle.Default
        if (fontFamily != null) style = style.copy(fontFamily = fontFamily)
        if (fontSize != androidx.compose.ui.unit.TextUnit.Unspecified) style = style.copy(fontSize = fontSize)
        if (fontWeight != null) style = style.copy(fontWeight = fontWeight)
        if (color != null) style = style.copy(color = color)
        if (lineHeight != androidx.compose.ui.unit.TextUnit.Unspecified) style = style.copy(lineHeight = lineHeight)
        if (letterSpacing != androidx.compose.ui.unit.TextUnit.Unspecified) style = style.copy(letterSpacing = letterSpacing)
        return style
    }

    /** 工具 / 终端输出正文。 */
    fun monoSmall(color: Color? = null): TextStyle =
        mono(size = 12, color = color, lineHeight = 18)

    /** 参数摘要、命令行、路径 chip、diff 统计。 */
    fun monoLabel(color: Color? = null, weight: FontWeight? = null): TextStyle =
        mono(size = 12, color = color, weight = weight, lineHeight = 16)

    /** 欢迎语 / 空状态大字:大而轻,不靠粗。 */
    fun display(size: Int = 28, color: Color? = null): TextStyle = build(
        fontFamily = sansFamily,
        fontSize = size.sp,
        fontWeight = FontWeight.Light,
        lineHeight = (size * 1.25f).sp,
        color = color,
    )

    /** 字标。「DSH」无衬线粗体 + 轻微字距。 */
    fun wordmark(size: Int = 18, color: Color? = null): TextStyle = build(
        fontFamily = sansFamily,
        fontSize = size.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.6.sp,
        lineHeight = (size * 1.2f).sp,
        color = color,
    )

    /** 分区小标题(「最近会话」「引擎」):小字号 + 字距,替代粗分割线。 */
    fun eyebrow(color: Color? = null): TextStyle = build(
        fontFamily = sansFamily,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        lineHeight = 16.sp,
        color = color,
    )

    /**
     * Material 3 字阶(颜色在 theme 里按方案统一 apply)。
     *
     * | 角色 | 字号 / 行高 | 用途 |
     * |---|---|---|
     * | displaySmall | 28 / 1.25 w300 | 欢迎语、安装完成大字 |
     * | headlineMedium | 22 / 1.3 w600 | 页面标题(设置、引擎) |
     * | headlineSmall | 20 / 1.35 w600 | 对话框标题 |
     * | titleLarge | 18 / 1.4 w600 | 弹层标题、顶栏会话名 |
     * | titleMedium | 16 / 1.45 w600 | 卡片 / 列表标题 |
     * | titleSmall | 14 / 1.45 w600 | 工具行名、分组名 |
     * | bodyLarge | 16 / 1.65 | 聊天正文 |
     * | bodyMedium | 14 / 1.55 | 界面正文 |
     * | bodySmall | 12 / 1.5 | 说明、元信息 |
     * | labelLarge | 14 / 1.4 w600 | 按钮 |
     * | labelMedium | 12 / 1.35 w600 | Chip、胶囊 |
     * | labelSmall | 11 / 1.35 | 时间戳、角标 |
     */
    val textTheme: Typography = Typography(
        displayLarge = TextStyle(fontSize = 36.sp, fontWeight = FontWeight.Light, lineHeight = 43.sp),
        displayMedium = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Light, lineHeight = 38.sp),
        displaySmall = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Light, lineHeight = 35.sp),
        headlineLarge = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold, lineHeight = 34.sp),
        headlineMedium = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, lineHeight = 29.sp),
        headlineSmall = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 27.sp),
        titleLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, lineHeight = 25.sp),
        titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 23.sp),
        titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp),
        bodyLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal, lineHeight = 26.sp),
        bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 22.sp),
        bodySmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, lineHeight = 18.sp),
        labelLarge = TextStyle(
            fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp, letterSpacing = 0.1.sp,
        ),
        labelMedium = TextStyle(
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold, lineHeight = 16.sp, letterSpacing = 0.2.sp,
        ),
        labelSmall = TextStyle(
            fontSize = 11.sp, fontWeight = FontWeight.Normal, lineHeight = 15.sp, letterSpacing = 0.2.sp,
        ),
    )
}

/** 当前配色的材质扩展(CompositionLocal)。 */
val LocalDshSurfaces = staticCompositionLocalOf<DshSurfaces> {
    DshSurfaces.light(AppAccent.mist)
}

/** 当前配色的语义色扩展。 */
val LocalDshSemantic = staticCompositionLocalOf<DshSemanticColors> {
    DshSemanticColors.light()
}

/** 当前配色(强调色)。 */
val LocalAppAccent = staticCompositionLocalOf<AppAccent> { AppAccent.mist }

/** 便捷读取:任何 Composable 里 `DshSurfaces.current` 就是当前材质。 */
object DshSurfacesAccess {
    val current: DshSurfaces
        @Composable get() = LocalDshSurfaces.current
}

/** 常用圆角:卡片 / 气泡 / 对话框 / 胶囊。 */
object AppRadius {
    fun xs() = RoundedCornerShape(AppShape.xs)
    fun sm() = RoundedCornerShape(AppShape.sm)
    fun md() = RoundedCornerShape(AppShape.md)
    fun lg() = RoundedCornerShape(AppShape.lg)
    fun xl() = RoundedCornerShape(AppShape.xl)

    /** 底部弹层:只有上面两个角。 */
    fun sheet() = RoundedCornerShape(
        topStart = AppShape.xl,
        topEnd = AppShape.xl,
    )

    /** 抽屉:只有右侧两个角。 */
    fun drawer() = RoundedCornerShape(
        topEnd = AppShape.xl,
        bottomEnd = AppShape.xl,
    )

    /** 胶囊。 */
    fun pill() = RoundedCornerShape(percent = 50)
}

/**
 * 生成 Material3 的 ColorScheme。
 *
 * 手工指定而不是 `dynamicColorScheme` / fromSeed:自动推导会把任何种子推成高彩度
 * 色阶,恰好抹掉这里要的低饱和。
 *
 * 层次规则:surface 系列按"越高越深"排(浅色)/ "越高越亮"排(深色),
 * surfaceTint 一律透明 —— 层次靠玻璃和细线,不靠 elevation 着色。
 */
fun dshColorScheme(accent: AppAccent, dark: Boolean): androidx.compose.material3.ColorScheme {
    val n = NeutralTones.forAccent(accent)
    val sem = DshSemanticColors.forAccent(dark, accent)
    return if (dark) {
        darkColorScheme(
            primary = accent.seedDark,
            onPrimary = accent.onSeedDark,
            primaryContainer = accent.containerDark,
            onPrimaryContainer = accent.onContainerDark,
            secondary = n.slateLift,
            onSecondary = n.onSlateLift,
            secondaryContainer = DshPalette.slateContainerDark,
            onSecondaryContainer = n.paper,
            tertiary = DshPalette.duskLift,
            onTertiary = DshPalette.onDuskLift,
            tertiaryContainer = DshPalette.duskContainerDark,
            onTertiaryContainer = DshPalette.onDuskContainerDark,
            error = sem.danger,
            onError = sem.onDanger,
            errorContainer = sem.dangerContainer,
            onErrorContainer = sem.onDangerContainer,
            background = n.night,
            onBackground = n.paper,
            surface = n.night,
            onSurface = n.paper,
            surfaceVariant = n.nightContainer,
            onSurfaceVariant = n.paperMuted,
            surfaceDim = n.nightLowest,
            surfaceBright = n.nightHighest,
            surfaceContainerLowest = n.nightLowest,
            surfaceContainerLow = n.nightLow,
            surfaceContainer = n.nightContainer,
            surfaceContainerHigh = n.nightHigh,
            surfaceContainerHighest = n.nightHighest,
            surfaceTint = Color.Transparent,
            inverseSurface = n.paper,
            inverseOnSurface = n.ink,
            inversePrimary = accent.seed,
            outline = n.outlineDark,
            outlineVariant = n.ruleDark,
            scrim = Color.Black,
        )
    } else {
        lightColorScheme(
            primary = accent.seed,
            onPrimary = accent.onSeed,
            primaryContainer = accent.container,
            onPrimaryContainer = accent.onContainer,
            secondary = n.slate,
            onSecondary = Color.White,
            secondaryContainer = n.slateContainer,
            onSecondaryContainer = n.ink,
            tertiary = DshPalette.dusk,
            onTertiary = Color.White,
            tertiaryContainer = DshPalette.duskContainer,
            onTertiaryContainer = DshPalette.onDuskContainer,
            error = sem.danger,
            onError = sem.onDanger,
            errorContainer = sem.dangerContainer,
            onErrorContainer = sem.onDangerContainer,
            background = n.mist,
            onBackground = n.ink,
            surface = n.mist,
            onSurface = n.ink,
            surfaceVariant = n.mistContainer,
            onSurfaceVariant = n.inkMuted,
            surfaceDim = n.mistHighest,
            surfaceBright = n.mistLowest,
            surfaceContainerLowest = n.mistLowest,
            surfaceContainerLow = n.mistLow,
            surfaceContainer = n.mistContainer,
            surfaceContainerHigh = n.mistHigh,
            surfaceContainerHighest = n.mistHighest,
            surfaceTint = Color.Transparent,
            inverseSurface = n.inverseSurface,
            inverseOnSurface = n.onInverseSurface,
            inversePrimary = accent.seedDark,
            outline = n.outlineLight,
            outlineVariant = n.ruleLight,
            scrim = n.scrim,
        )
    }
}
