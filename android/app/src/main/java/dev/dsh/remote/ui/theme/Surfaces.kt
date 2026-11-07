package dev.dsh.remote.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

/**
 * 玻璃层级。决定填充透明度、是否模糊、描边与阴影。
 *
 * | 层级 | 用在哪些地方 | 模糊 |
 * |---|---|---|
 * | [chrome] | 顶栏胶囊、圆钮、输入条、底栏 —— 浮在滚动内容上的 | 有 |
 * | [sheet] | 底部弹层、菜单、抽屉 | 有 |
 * | [card] | 设置卡、审批卡、工具组 —— 躺在页面背景上的 | 无 |
 * | [pill] | 思考胶囊、模型胶囊、状态 chip —— 滚动列表里的小件 | 无 |
 *
 * 只有 chrome / sheet 真模糊:它们背后是会滚动的内容,模糊才有意义;card / pill
 * 背后只是静态渐变,半透明白就已经读作"磨砂",再上模糊只是白白烧 GPU。
 */
enum class GlassTier { Chrome, Sheet, Card, Pill }

/** 一种玻璃材质的全部参数。 */
data class GlassStyle(
    /** 开启模糊时的填充(带透明度)。 */
    val fill: Color,
    /** 关闭模糊时的填充(低端机、省电、减少透明度、高对比度模式)。必须接近不透明。 */
    val fallbackFill: Color,
    /** 发丝描边:界定玻璃边缘。浅色是冷墨 8%,深色是白 9%。 */
    val hairline: Color,
    /** 顶边高光起始色:向下 45% 处渐隐到透明 —— 光从上方打在玻璃边上。 */
    val rim: Color,
    /** 高斯模糊 σ(逻辑像素)。0 = 不模糊。 */
    val blurSigma: Float = 0f,
    /** 模糊后的饱和度增益(1 = 不变)。iOS 式"透色":背后的颜色透过来更鲜一点。 */
    val saturation: Float = 1f,
    val hairlineWidth: Float = 0.5f,
    /** 只画在形状**外部**的投影(玻璃内部不被阴影弄脏)。 */
    val shadows: List<ShadowSpec> = emptyList(),
) {
    val blurs: Boolean get() = blurSigma > 0f
}

/** 一条投影的描述(与 Compose 的 shadow 参数解耦,便于 lerp)。 */
data class ShadowSpec(
    val color: Color,
    val blurRadius: Float,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
) {
    fun toDp() = Triple(color, blurRadius.dp, offsetX.dp to offsetY.dp)
}

/**
 * 页面材质:背景渐变、光晕、用户气泡、四种玻璃。
 *
 * 与 [DshSemanticColors] 不同,这里**随 accent 动**(光晕与气泡带主色调),也随
 * "是否允许模糊"变。
 */
data class DshSurfaces(
    /** 页面竖向渐变的顶 / 底。 */
    val backdropTop: Color,
    val backdropBottom: Color,
    /** 右上角主色光晕(已含透明度)。 */
    val glowPrimary: Color,
    /** 左下角光晕(已含透明度):配主色的邻近色。 */
    val glowSecondary: Color,
    /** 用户气泡:比页面深一档的主色系。 */
    val userBubble: Color,
    val onUserBubble: Color,
    /** 抽屉 / 模态遮罩(已含透明度)。 */
    val scrim: Color,
    val chrome: GlassStyle,
    val sheet: GlassStyle,
    val card: GlassStyle,
    val pill: GlassStyle,
    /** 这套材质出自哪套配色。 */
    val accent: AppAccent,
    /** 总开关。关掉后所有层级都走 `fallbackFill`,不再创建模糊层。 */
    val blurEnabled: Boolean,
) {
    fun glass(tier: GlassTier): GlassStyle = when (tier) {
        GlassTier.Chrome -> chrome
        GlassTier.Sheet -> sheet
        GlassTier.Card -> card
        GlassTier.Pill -> pill
    }

    fun withBlur(enabled: Boolean): DshSurfaces = copy(blurEnabled = enabled)

    companion object {
        /**
         * 浅色:晨雾渐变 + 白玻璃。渐变、阴影、实色兜底跟配色的中性色走;
         * 玻璃本身是白的,高光也是白的 —— 换了配色,玻璃还是玻璃。
         */
        fun light(accent: AppAccent, blurEnabled: Boolean = true): DshSurfaces {
            val n = NeutralTones.forAccent(accent)
            val ink = n.shadowInk
            val white = Color.White
            return DshSurfaces(
                backdropTop = n.mistTop,
                backdropBottom = n.mistBottom,
                glowPrimary = accent.glow.copy(alpha = 0.42f),
                glowSecondary = accent.haze.copy(alpha = 0.38f),
                userBubble = accent.bubble,
                onUserBubble = n.ink,
                scrim = n.scrim.copy(alpha = 0.32f),
                accent = accent,
                blurEnabled = blurEnabled,
                chrome = GlassStyle(
                    fill = white.copy(alpha = 0.70f),
                    fallbackFill = n.mistLowest.copy(alpha = 0.96f),
                    hairline = ink.copy(alpha = 0.08f),
                    rim = white.copy(alpha = 0.85f),
                    blurSigma = 18f,
                    saturation = 1.3f,
                    shadows = listOf(
                        ShadowSpec(ink.copy(alpha = 0.08f), 24f, 0f, 8f),
                        ShadowSpec(ink.copy(alpha = 0.05f), 3f, 0f, 1f),
                    ),
                ),
                sheet = GlassStyle(
                    fill = n.mistLow.copy(alpha = 0.82f),
                    fallbackFill = n.mistLow,
                    hairline = ink.copy(alpha = 0.07f),
                    rim = white.copy(alpha = 0.90f),
                    blurSigma = 24f,
                    saturation = 1.4f,
                    shadows = listOf(ShadowSpec(ink.copy(alpha = 0.10f), 32f, 0f, 8f)),
                ),
                card = GlassStyle(
                    fill = white.copy(alpha = 0.62f),
                    fallbackFill = white.copy(alpha = 0.72f),
                    hairline = ink.copy(alpha = 0.06f),
                    rim = white.copy(alpha = 0.70f),
                ),
                pill = GlassStyle(
                    fill = white.copy(alpha = 0.55f),
                    fallbackFill = white.copy(alpha = 0.66f),
                    hairline = ink.copy(alpha = 0.07f),
                    rim = white.copy(alpha = 0.75f),
                ),
            )
        }

        /** 深色:深海石板渐变 + 烟灰玻璃(石板与烟灰跟配色的中性色走)。 */
        fun dark(accent: AppAccent, blurEnabled: Boolean = true): DshSurfaces {
            val n = NeutralTones.forAccent(accent)
            val white = Color.White
            val black = Color.Black
            val smoke = n.smoke
            return DshSurfaces(
                backdropTop = n.nightTop,
                backdropBottom = n.nightBottom,
                glowPrimary = accent.glowDark.copy(alpha = 0.28f),
                glowSecondary = accent.hazeDark.copy(alpha = 0.24f),
                userBubble = accent.bubbleDark,
                onUserBubble = n.paper,
                scrim = black.copy(alpha = 0.48f),
                accent = accent,
                blurEnabled = blurEnabled,
                chrome = GlassStyle(
                    fill = smoke.copy(alpha = 0.72f),
                    fallbackFill = smoke.copy(alpha = 0.97f),
                    hairline = white.copy(alpha = 0.09f),
                    rim = white.copy(alpha = 0.12f),
                    blurSigma = 18f,
                    saturation = 1.2f,
                    shadows = listOf(
                        ShadowSpec(black.copy(alpha = 0.35f), 24f, 0f, 8f),
                        ShadowSpec(black.copy(alpha = 0.25f), 3f, 0f, 1f),
                    ),
                ),
                sheet = GlassStyle(
                    fill = n.sheetDark.copy(alpha = 0.86f),
                    fallbackFill = n.sheetDark,
                    hairline = white.copy(alpha = 0.08f),
                    rim = white.copy(alpha = 0.10f),
                    blurSigma = 24f,
                    saturation = 1.2f,
                    shadows = listOf(ShadowSpec(black.copy(alpha = 0.40f), 32f, 0f, 8f)),
                ),
                // 深色卡片:填充是 5% 白,但**兜底**必须接近不透明 —— 关掉透明效果时
                // 没有取样也没有模糊,半透明会让背后的渐变和滚动内容透上来,把字搅花。
                // 所以兜底用"石板底 + 一点白"的实色,而不是把 fill 原样端上去。
                card = GlassStyle(
                    fill = white.copy(alpha = 0.05f),
                    fallbackFill = n.nightContainer,
                    hairline = white.copy(alpha = 0.07f),
                    rim = white.copy(alpha = 0.06f),
                ),
                pill = GlassStyle(
                    fill = white.copy(alpha = 0.06f),
                    fallbackFill = n.nightHigh,
                    hairline = white.copy(alpha = 0.08f),
                    rim = white.copy(alpha = 0.08f),
                ),
            )
        }

        /** 主题切换时的插值(颜色与数值逐项混合)。 */
        fun lerp(a: DshSurfaces, b: DshSurfaces, t: Float): DshSurfaces {
            fun mix(x: Color, y: Color) = lerp(x, y, t)
            fun mixStyle(x: GlassStyle, y: GlassStyle) = GlassStyle(
                fill = mix(x.fill, y.fill),
                fallbackFill = mix(x.fallbackFill, y.fallbackFill),
                hairline = mix(x.hairline, y.hairline),
                rim = mix(x.rim, y.rim),
                blurSigma = x.blurSigma + (y.blurSigma - x.blurSigma) * t,
                saturation = x.saturation + (y.saturation - x.saturation) * t,
                hairlineWidth = x.hairlineWidth + (y.hairlineWidth - x.hairlineWidth) * t,
                shadows = if (x.shadows.size == y.shadows.size) {
                    x.shadows.mapIndexed { i, s ->
                        val o = y.shadows[i]
                        ShadowSpec(
                            color = mix(s.color, o.color),
                            blurRadius = s.blurRadius + (o.blurRadius - s.blurRadius) * t,
                            offsetX = s.offsetX + (o.offsetX - s.offsetX) * t,
                            offsetY = s.offsetY + (o.offsetY - s.offsetY) * t,
                        )
                    }
                } else {
                    if (t < 0.5f) x.shadows else y.shadows
                },
            )
            return DshSurfaces(
                backdropTop = mix(a.backdropTop, b.backdropTop),
                backdropBottom = mix(a.backdropBottom, b.backdropBottom),
                glowPrimary = mix(a.glowPrimary, b.glowPrimary),
                glowSecondary = mix(a.glowSecondary, b.glowSecondary),
                userBubble = mix(a.userBubble, b.userBubble),
                onUserBubble = mix(a.onUserBubble, b.onUserBubble),
                scrim = mix(a.scrim, b.scrim),
                chrome = mixStyle(a.chrome, b.chrome),
                sheet = mixStyle(a.sheet, b.sheet),
                card = mixStyle(a.card, b.card),
                pill = mixStyle(a.pill, b.pill),
                accent = if (t < 0.5f) a.accent else b.accent,
                blurEnabled = if (t < 0.5f) a.blurEnabled else b.blurEnabled,
            )
        }
    }
}
