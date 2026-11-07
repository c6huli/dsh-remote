package dev.dsh.remote.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 雾蓝玻璃(Mist Glass)的原始色值:手工构造,不走 Material 的自动色阶。
 *
 * 自动生成的色阶会把任何种子色推成一套高彩度"现代 App"配色,恰好抹掉这里要的
 * 低饱和。所以每个槽位手工指定。
 *
 * ## 两套印次
 *
 * - 浅色:**晨雾蓝纸面**。页面是一条极淡的竖向渐变(顶 `#EBF1FE` → 底 `#F6F7FC`),
 *   彩度 ≤ 0.019。
 * - 深色:**深海石板**。明度 0.23–0.25,与 Claude 深色同一档,**不是**纯黑科技风:
 *   纯黑底上的亮字长时间看会"晕"。
 * - 浅深都**不用**纯白 / 纯黑做大面积面色;纯白只出现在玻璃高光和主按钮字上。
 *
 * ## 比例
 *
 * 70% 中性面(渐变 + 玻璃)、25% 文字与细线、≤5% 强调色。强调色只出现在主按钮、
 * 发送键、选中态、链接、进度。铺多了就不"静"了。
 */
object DshPalette {
    // ══ 浅色:晨雾 ══════════════════════════════════════════
    /** 页面渐变顶 · 晨雾蓝(与 DSH 官方 deepseek-50 同族)。 */
    val mistTop = Color(0xFFEBF1FE)

    /** 页面渐变底 · 近白微紫。 */
    val mistBottom = Color(0xFFF6F7FC)

    /** 不透明页面底(渐变中值;无法铺渐变时用)。 */
    val mist = Color(0xFFF0F4FD)

    /** 面色阶梯(不透明),玻璃关闭或 M3 组件需要实色时用。 */
    val mistLowest = Color(0xFFFBFCFF)
    val mistLow = Color(0xFFF6F9FE)
    val mistContainer = Color(0xFFEEF1F9)
    val mistHigh = Color(0xFFE7EBF4)
    val mistHighest = Color(0xFFDFE4EE)

    /** 主文字 · 深墨蓝(对所有浅色面 ≥ 13)。 */
    val ink = Color(0xFF1E232E)

    /** 次级文字(≥ 6.2)。 */
    val inkMuted = Color(0xFF4F5562)

    /** 三级文字 · 时间戳、摘要、占位符(≥ 4.6,仍可承载信息)。 */
    val inkFaint = Color(0xFF646974)

    /** 禁用 / 纯装饰(约 2.5,**不得**承载必要信息)。 */
    val inkGhost = Color(0xFFA1A5AC)

    /** 输入框等交互边界(非文字对比 ≥ 3)。 */
    val outlineLight = Color(0xFF7E838D)

    /** 分割线 / 发丝描边(装饰性)。 */
    val ruleLight = Color(0xFFDCE0E8)

    /** 冷墨阴影基色:阴影带一点蓝,比纯黑阴影干净。 */
    val shadowInk = Color(0xFF0B152D)

    // ══ 深色:深海石板 ══════════════════════════════════════
    val nightTop = Color(0xFF1D222C)
    val nightBottom = Color(0xFF1B1C23)
    val night = Color(0xFF1C1F28)
    val nightLowest = Color(0xFF14171E)
    val nightLow = Color(0xFF232730)
    val nightContainer = Color(0xFF272C36)
    val nightHigh = Color(0xFF2D323C)
    val nightHighest = Color(0xFF343944)

    /** 主文字 · 雾白(不是纯白,对深色面 ≈ 10.5)。 */
    val paper = Color(0xFFE6E9F0)
    val paperMuted = Color(0xFFB6BBC5)
    val paperFaint = Color(0xFF9A9EA9)
    val paperGhost = Color(0xFF656971)
    val outlineDark = Color(0xFF737781)
    val ruleDark = Color(0xFF343841)
    val shadowNight = Color(0xFF03060D)

    // ══ 中性辅助色(两套共用)══════════════════════════════════
    /** 浅色 secondary · 板岩蓝灰:次要按钮、未选中强调。 */
    val slate = Color(0xFF545E6F)
    val slateContainer = Color(0xFFE2E6F0)

    /** 深色 secondary。tonal 按钮底是叠在玻璃卡上纯 9% 的白,和卡片拉开层次。 */
    val slateLift = Color(0xFFB3BED2)
    val onSlateLift = Color(0xFF1A2230)
    val slateContainerDark = Color(0x17FFFFFF)

    /** tertiary · 暮紫:计划 / 目标 / 子代理这类"智能体在规划"的点缀。 */
    val dusk = Color(0xFF6B578F)
    val duskContainer = Color(0xFFECE5FB)
    val onDuskContainer = Color(0xFF3B2C54)
    val duskLift = Color(0xFFBEAEDE)
    val onDuskLift = Color(0xFF251C36)
    val duskContainerDark = Color(0xFF3E3451)
    val onDuskContainerDark = Color(0xFFE4DDF3)

    // ══ 错误(低饱和砖红,与 DshColors 的 danger 同值)══════════
    val danger = Color(0xFFA54743)
    val onDanger = Color(0xFFFDF9F8)
    val dangerContainer = Color(0xFFFFE2E0)
    val onDangerContainer = Color(0xFF642724)
    val dangerLift = Color(0xFFE69089)
    val onDangerLift = Color(0xFF331513)
    val dangerContainerDark = Color(0xFF542C29)
    val onDangerContainerDark = Color(0xFFF8D9D7)

    /** 遮罩基色(抽屉、模态底)。 */
    val scrim = Color(0xFF0B111F)
}

/**
 * 一套配色下的全部中性色:页面渐变、面色阶梯、各级文字、细线、阴影、代码井、终端。
 *
 * [defaults] 就是 [DshPalette] 里那些常量;别的配色用 [of] 把它整条转到
 * `accent.tintHue` 与 `accent.tintChroma` 上,**明度一点不动** —— 所以文字对比度、
 * 玻璃层次在每套配色里都一样。
 */
data class NeutralTones(
    // 浅色
    val mistTop: Color,
    val mistBottom: Color,
    val mist: Color,
    val mistLowest: Color,
    val mistLow: Color,
    val mistContainer: Color,
    val mistHigh: Color,
    val mistHighest: Color,
    val ink: Color,
    val inkMuted: Color,
    val inkFaint: Color,
    val inkGhost: Color,
    val outlineLight: Color,
    val ruleLight: Color,
    val shadowInk: Color,
    val slate: Color,
    val slateContainer: Color,
    val inverseSurface: Color,
    val onInverseSurface: Color,
    val scrim: Color,
    // 浅色代码井
    val codeWell: Color,
    val codeWellHeader: Color,
    val codeWellBorder: Color,
    val lineNumber: Color,
    val comment: Color,
    // 深色
    val nightTop: Color,
    val nightBottom: Color,
    val night: Color,
    val nightLowest: Color,
    val nightLow: Color,
    val nightContainer: Color,
    val nightHigh: Color,
    val nightHighest: Color,
    val paper: Color,
    val paperMuted: Color,
    val paperFaint: Color,
    val paperGhost: Color,
    val outlineDark: Color,
    val ruleDark: Color,
    val shadowNight: Color,
    val slateLift: Color,
    val onSlateLift: Color,
    // 深色玻璃
    val smoke: Color,
    val sheetDark: Color,
    // 深色代码井
    val codeWellDark: Color,
    val codeWellHeaderDark: Color,
    val codeWellBorderDark: Color,
    val lineNumberDark: Color,
    val commentDark: Color,
    // 终端(浅色模式也是深底)
    val terminal: Color,
    val terminalKey: Color,
) {
    companion object {
        /** 雾蓝中性色所在的色相。 */
        const val BASE_HUE = 266.0

        /** 雾蓝的中性色(就是 [DshPalette] 的常量)。 */
        val defaults = NeutralTones(
            mistTop = DshPalette.mistTop,
            mistBottom = DshPalette.mistBottom,
            mist = DshPalette.mist,
            mistLowest = DshPalette.mistLowest,
            mistLow = DshPalette.mistLow,
            mistContainer = DshPalette.mistContainer,
            mistHigh = DshPalette.mistHigh,
            mistHighest = DshPalette.mistHighest,
            ink = DshPalette.ink,
            inkMuted = DshPalette.inkMuted,
            inkFaint = DshPalette.inkFaint,
            inkGhost = DshPalette.inkGhost,
            outlineLight = DshPalette.outlineLight,
            ruleLight = DshPalette.ruleLight,
            shadowInk = DshPalette.shadowInk,
            slate = DshPalette.slate,
            slateContainer = DshPalette.slateContainer,
            inverseSurface = Color(0xFF252A35),
            onInverseSurface = Color(0xFFECEEF4),
            scrim = DshPalette.scrim,
            codeWell = Color(0xFFEDF0F6),
            codeWellHeader = Color(0xFFE7EBF3),
            codeWellBorder = Color(0xFFDBDEE5),
            lineNumber = Color(0xFF7D8088),
            comment = Color(0xFF636975),
            nightTop = DshPalette.nightTop,
            nightBottom = DshPalette.nightBottom,
            night = DshPalette.night,
            nightLowest = DshPalette.nightLowest,
            nightLow = DshPalette.nightLow,
            nightContainer = DshPalette.nightContainer,
            nightHigh = DshPalette.nightHigh,
            nightHighest = DshPalette.nightHighest,
            paper = DshPalette.paper,
            paperMuted = DshPalette.paperMuted,
            paperFaint = DshPalette.paperFaint,
            paperGhost = DshPalette.paperGhost,
            outlineDark = DshPalette.outlineDark,
            ruleDark = DshPalette.ruleDark,
            shadowNight = DshPalette.shadowNight,
            slateLift = DshPalette.slateLift,
            onSlateLift = DshPalette.onSlateLift,
            smoke = Color(0xFF292E39),
            sheetDark = Color(0xFF232832),
            codeWellDark = Color(0xFF14171E),
            codeWellHeaderDark = Color(0xFF1A1E26),
            codeWellBorderDark = Color(0xFF2F333A),
            lineNumberDark = Color(0xFF71747D),
            commentDark = Color(0xFF868C97),
            terminal = Color(0xFF12151C),
            terminalKey = Color(0xFF262A33),
        )

        private val cache = HashMap<Long, NeutralTones>()

        fun forAccent(accent: AppAccent): NeutralTones =
            of(accent.tintHue, accent.tintChroma)

        /**
         * 转到 [tintHue] 色相、彩度乘 [chromaScale] 的整条中性色。结果按参数缓存。
         */
        fun of(tintHue: Double, tintChroma: Double): NeutralTones {
            if (tintHue == BASE_HUE && tintChroma == 1.0) return defaults
            // 参数只有两个 double,打包成一个 key 缓存,避免每次重组都重算 50 个颜色。
            val key = (tintHue * 1000).toLong() * 100000L + (tintChroma * 1000).toLong()
            cache[key]?.let { return it }
            if (cache.size > 32) cache.clear()
            val shift = tintHue - BASE_HUE
            fun t(c: Color) = retint(c, shift, tintChroma)
            val m = defaults
            val value = NeutralTones(
                mistTop = t(m.mistTop),
                mistBottom = t(m.mistBottom),
                mist = t(m.mist),
                mistLowest = t(m.mistLowest),
                mistLow = t(m.mistLow),
                mistContainer = t(m.mistContainer),
                mistHigh = t(m.mistHigh),
                mistHighest = t(m.mistHighest),
                ink = t(m.ink),
                inkMuted = t(m.inkMuted),
                inkFaint = t(m.inkFaint),
                inkGhost = t(m.inkGhost),
                outlineLight = t(m.outlineLight),
                ruleLight = t(m.ruleLight),
                shadowInk = t(m.shadowInk),
                slate = t(m.slate),
                slateContainer = t(m.slateContainer),
                inverseSurface = t(m.inverseSurface),
                onInverseSurface = t(m.onInverseSurface),
                scrim = t(m.scrim),
                codeWell = t(m.codeWell),
                codeWellHeader = t(m.codeWellHeader),
                codeWellBorder = t(m.codeWellBorder),
                lineNumber = t(m.lineNumber),
                comment = t(m.comment),
                nightTop = t(m.nightTop),
                nightBottom = t(m.nightBottom),
                night = t(m.night),
                nightLowest = t(m.nightLowest),
                nightLow = t(m.nightLow),
                nightContainer = t(m.nightContainer),
                nightHigh = t(m.nightHigh),
                nightHighest = t(m.nightHighest),
                paper = t(m.paper),
                paperMuted = t(m.paperMuted),
                paperFaint = t(m.paperFaint),
                paperGhost = t(m.paperGhost),
                outlineDark = t(m.outlineDark),
                ruleDark = t(m.ruleDark),
                shadowNight = t(m.shadowNight),
                slateLift = t(m.slateLift),
                onSlateLift = t(m.onSlateLift),
                smoke = t(m.smoke),
                sheetDark = t(m.sheetDark),
                codeWellDark = t(m.codeWellDark),
                codeWellHeaderDark = t(m.codeWellHeaderDark),
                codeWellBorderDark = t(m.codeWellBorderDark),
                lineNumberDark = t(m.lineNumberDark),
                commentDark = t(m.commentDark),
                terminal = t(m.terminal),
                terminalKey = t(m.terminalKey),
            )
            cache[key] = value
            return value
        }
    }
}
