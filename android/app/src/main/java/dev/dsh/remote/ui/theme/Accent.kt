package dev.dsh.remote.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.min

/**
 * 一套配色的强调色:浅深两套色阶,外加中性色要往哪个色调上染。
 *
 * ## 推导规则(OKLCH,所有色相同一把尺子)
 *
 * DeepSeek 品牌蓝 `#4D6BFE` 是 OKLCH(0.59, 0.22, 270):饱和度几乎顶格,大面积
 * 看久了刺眼。默认 [mist] 保留它的色相(267°),彩度压到 0.125、明度 0.53,
 * 白字对比 5.39。其余每套只换色相和彩度,明度照抄:
 *
 * | 角色 | 明度 L | 彩度(× 强调彩度) |
 * |---|---|---|
 * | seed / pressed | 0.53 / 0.47 | 1 / 0.95 |
 * | container / onContainer | 0.93 / 0.33 | 0.26 / 0.70 |
 * | bubble / glow | 0.918 / 0.819 | 0.29 / 0.68 |
 * | seedDark / pressedDark | 0.764 / 0.704 | 0.76 |
 * | onSeedDark | 0.231 | 0.39 |
 * | containerDark / onContainerDark | 0.361 / 0.909 | 0.51 / 0.24 |
 * | bubbleDark / glowDark | 0.314 / 0.52 | 0.29 / 0.72 |
 *
 * - 浅色主色上用白字(≥ 4.5),深色主色上用同色相的深墨字(≥ 7)。
 * - 中性色:雾蓝那条中性色阶原样转到 [tintHue],彩度乘 [tintChroma]。明度不动,
 *   所以各级文字的对比度不随配色变。
 * - [haze]:页面左下角那团光,配主色的邻近色,给玻璃一点可透的颜色。
 */
data class AppAccent(
    /** 持久化键:预设名,或 "custom"。 */
    val id: String,
    /** 设置页展示名(自定义色为空,由 UI 兜底)。 */
    val label: String?,
    /** 浅色主色(色板上显示的颜色)。白字 ≥ 4.5。 */
    val seed: Color,
    /** 浅色按下态。 */
    val pressed: Color,
    /** 浅色:强调容器 / 其上前景(≥ 7)。 */
    val container: Color,
    val onContainer: Color,
    /** 浅色用户气泡:比页面深一档(OKLCH L 0.918),像轻轻按进纸里的一块。 */
    val bubble: Color,
    /** 浅色背景光晕(使用时再乘透明度)。 */
    val glow: Color,
    /** 深色主色:提亮版,配 [onSeedDark] 深墨字。 */
    val seedDark: Color,
    val pressedDark: Color,
    val onSeedDark: Color,
    val containerDark: Color,
    val onContainerDark: Color,
    val bubbleDark: Color,
    val glowDark: Color,
    /** 中性色转到的色相(度)。雾蓝的中性色在 [NeutralTones.BASE_HUE]。 */
    val tintHue: Double,
    /** 中性色的彩度倍数(雾蓝 = 1;石墨接近 0)。 */
    val tintChroma: Double,
    /** 左下角光晕(浅)。使用时再乘透明度。 */
    val haze: Color,
    val hazeDark: Color,
) {
    /** 浅色主色上的文字。所有配色都是白字。 */
    val onSeed: Color get() = Color.White

    /** 按亮度取主色。 */
    fun seedFor(dark: Boolean): Color = if (dark) seedDark else seed

    companion object {
        /** 默认:雾蓝。DeepSeek 色相,低彩度。 */
        val mist = AppAccent(
            id = "mist",
            label = null,
            seed = Color(0xFF4B67B4),
            pressed = Color(0xFF3D569D),
            container = Color(0xFFDEE8FF),
            onContainer = Color(0xFF213262),
            bubble = Color(0xFFD9E4FD),
            glow = Color(0xFFABC3FC),
            seedDark = Color(0xFF97B1F0),
            pressedDark = Color(0xFF859EDC),
            onSeedDark = Color(0xFF121C34),
            containerDark = Color(0xFF2E3C5F),
            onContainerDark = Color(0xFFD8E1F6),
            bubbleDark = Color(0xFF293144),
            glowDark = Color(0xFF51679D),
            tintHue = NeutralTones.BASE_HUE,
            tintChroma = 1.0,
            haze = Color(0xFFD5CBEB),
            hazeDark = Color(0xFF514765),
        )

        /** 黛青:墨蓝偏青(238°),比雾蓝沉一档(L 0.47),彩度收到 0.085。 */
        val daiqing = AppAccent(
            id = "daiqing",
            label = null,
            seed = Color(0xFF236185),
            pressed = Color(0xFF145071),
            container = Color(0xFFDBEAF5),
            onContainer = Color(0xFF123950),
            bubble = Color(0xFFD6E7F3),
            glow = Color(0xFFA2CAE5),
            seedDark = Color(0xFF8CB9D7),
            pressedDark = Color(0xFF79A6C4),
            onSeedDark = Color(0xFF0D1F2B),
            containerDark = Color(0xFF274152),
            onContainerDark = Color(0xFFD5E3ED),
            bubbleDark = Color(0xFF26333C),
            glowDark = Color(0xFF466E88),
            tintHue = 238.0,
            tintChroma = 0.9,
            haze = Color(0xFFC7D0EC),
            hazeDark = Color(0xFF444C66),
        )

        /** 暮紫:灰调藕荷(300°,彩度 0.075)。光晕配一点灰玫瑰。 */
        val dusk = AppAccent(
            id = "dusk",
            label = null,
            seed = Color(0xFF726291),
            pressed = Color(0xFF61517D),
            container = Color(0xFFEAE5F4),
            onContainer = Color(0xFF392F4C),
            bubble = Color(0xFFE6E1F1),
            glow = Color(0xFFC8BDE1),
            seedDark = Color(0xFFB8ABD2),
            pressedDark = Color(0xFFA598BF),
            onSeedDark = Color(0xFF1F1A29),
            containerDark = Color(0xFF40394F),
            onContainerDark = Color(0xFFE2DEEC),
            bubbleDark = Color(0xFF332F3B),
            glowDark = Color(0xFF6D6284),
            tintHue = 295.0,
            tintChroma = 0.85,
            haze = Color(0xFFE6C7D7),
            hazeDark = Color(0xFF5F4353),
        )

        /** 松石:低饱和青绿(186°,彩度 0.068)。中性色只带三成彩,免得发绿发灰。 */
        val turquoise = AppAccent(
            id = "turquoise",
            label = null,
            seed = Color(0xFF34766F),
            pressed = Color(0xFF296760),
            container = Color(0xFFDBECE9),
            onContainer = Color(0xFF123D39),
            bubble = Color(0xFFD6E8E6),
            glow = Color(0xFFA3CEC8),
            seedDark = Color(0xFF8DBEB7),
            pressedDark = Color(0xFF7BABA4),
            onSeedDark = Color(0xFF0D211F),
            containerDark = Color(0xFF274340),
            onContainerDark = Color(0xFFD6E5E2),
            bubbleDark = Color(0xFF263533),
            glowDark = Color(0xFF47726D),
            tintHue = 190.0,
            tintChroma = 0.7,
            haze = Color(0xFFB9D7E3),
            hazeDark = Color(0xFF34525E),
        )

        /** 琥珀:克制的金棕(70°,彩度 0.082,L 0.54)。页面转成米白纸。 */
        val amber = AppAccent(
            id = "amber",
            label = null,
            seed = Color(0xFF8D6636),
            pressed = Color(0xFF7A5528),
            container = Color(0xFFF2E6D9),
            onContainer = Color(0xFF483011),
            bubble = Color(0xFFEEE2D3),
            glow = Color(0xFFDCBE9D),
            seedDark = Color(0xFFCCAC87),
            pressedDark = Color(0xFFB99975),
            onSeedDark = Color(0xFF271A0C),
            containerDark = Color(0xFF4C3A25),
            onContainerDark = Color(0xFFEADFD3),
            bubbleDark = Color(0xFF392F24),
            glowDark = Color(0xFF7F6343),
            tintHue = 75.0,
            tintChroma = 0.75,
            haze = Color(0xFFEAC8C1),
            hazeDark = Color(0xFF64443D),
        )

        /** 石墨:中性灰。主色在浅色里近黑(L 0.30)、深色里近白(L 0.93)。 */
        val graphite = AppAccent(
            id = "graphite",
            label = null,
            seed = Color(0xFF2C2E32),
            pressed = Color(0xFF1D1F23),
            container = Color(0xFFE3E6EC),
            onContainer = Color(0xFF2C2E32),
            bubble = Color(0xFFE0E3E8),
            glow = Color(0xFFBBBEC3),
            seedDark = Color(0xFFE5E8ED),
            pressedDark = Color(0xFFCED1D6),
            onSeedDark = Color(0xFF191B1E),
            containerDark = Color(0xFF3B3D41),
            onContainerDark = Color(0xFFDEE1E7),
            bubbleDark = Color(0xFF2E3034),
            glowDark = Color(0xFF616368),
            tintHue = 262.0,
            tintChroma = 0.25,
            haze = Color(0xFFCED1D6),
            hazeDark = Color(0xFF4A4D52),
        )

        /** 设置页里的顺序。 */
        val presets = listOf(mist, daiqing, dusk, turquoise, amber, graphite)

        const val CUSTOM_ID = "custom"

        /** 按 id 取预设;未知 / 缺失回落雾蓝(向前兼容旧值)。 */
        fun preset(id: String?): AppAccent = presets.firstOrNull { it.id == id } ?: mist

        /** 强调彩度上限:再高就不"安静"了(雾蓝 0.125)。 */
        const val MAX_CHROMA = 0.13

        /** 色相不变、推荐的明度(与雾蓝相同)。 */
        const val LIGHT_SEED_L = 0.53

        /** 深色主色比浅色主色亮多少(雾蓝 0.53 → 0.764)。 */
        const val DARK_LIFT = 0.234

        /** 强调色对底面色的最低对比度(WCAG 1.4.11 非文字)。 */
        const val MIN_ON_SURFACE = 3.0

        /** 强调色上文字的最低对比度(WCAG 1.4.3 正文)。 */
        const val MIN_TEXT = 4.5

        private val derivedCache = HashMap<String, AppAccent>()

        /**
         * 用任意一个颜色推出整套配色:取它的色相、彩度(封顶 [MAX_CHROMA])和明度
         * (限在 0.30–0.60,深色再提 [DARK_LIFT]),按上面那张表推出各个角色;
         * 浅色、深色主色各过一次 [guard],离原色最近地调到达标。
         *
         * 中性色转到同一色相,彩度按强调彩度折减(近灰的颜色得到近灰的页面)。
         */
        fun derive(pick: Color, id: String = CUSTOM_ID, label: String? = null): AppAccent {
            val key = "${pick.value}:$id:$label"
            derivedCache[key]?.let { return it }
            if (derivedCache.size > 64) derivedCache.clear()
            return build(pick, id, label).also { derivedCache[key] = it }
        }

        private fun build(pick: Color, id: String, label: String?): AppAccent {
            val o = Oklch.fromColor(pick)
            val c = min(o.c, MAX_CHROMA)
            val h = o.h
            val k = min(1.0, c / 0.1255)
            val tintChroma = 0.8 * k
            val neutrals = NeutralTones.of(tintHue = h, tintChroma = tintChroma)
            fun tone(l: Double, ratio: Double) = Oklch(l, c * ratio, h).toColor()

            val onSeedDark = tone(0.231, 0.39)
            val lightL = o.l.coerceIn(0.30, 0.60)
            val seed = guard(
                Oklch(lightL, c, h),
                dark = false,
                surface = neutrals.mistHighest,
                onAccent = Color.White,
            )
            val seedDark = guard(
                Oklch(min(0.93, lightL + DARK_LIFT), c * 0.76, h),
                dark = true,
                surface = neutrals.nightHighest,
                onAccent = onSeedDark,
            )
            val sl = Oklch.fromColor(seed).l
            val sdl = Oklch.fromColor(seedDark).l
            return AppAccent(
                id = id,
                label = label,
                seed = seed,
                pressed = tone(sl - 0.06, 0.95),
                container = tone(0.93, 0.26),
                onContainer = tone(0.33, 0.70),
                bubble = tone(0.918, 0.29),
                glow = tone(0.819, 0.68),
                seedDark = seedDark,
                pressedDark = tone(sdl - 0.06, 0.76),
                onSeedDark = onSeedDark,
                containerDark = tone(0.361, 0.51),
                onContainerDark = tone(0.909, 0.24),
                bubbleDark = tone(0.314, 0.29),
                glowDark = tone(0.52, 0.72),
                tintHue = h,
                tintChroma = tintChroma,
                haze = Oklch(0.86, 0.045 * k, h + 33).toColor(),
                hazeDark = Oklch(0.42, 0.05 * k, h + 33).toColor(),
            )
        }

        /**
         * 对比度守门:色相、彩度不动,只沿明度移动 [start],直到
         *
         * - 强调色对 [surface] ≥ [MIN_ON_SURFACE](3:1),且
         * - [onAccent] 对强调色 ≥ [MIN_TEXT](4.5:1)。
         *
         * 浅色往暗处走(白字在上面),深色往亮处走(深墨字在上);已经达标就原样返回。
         */
        fun guard(start: Oklch, dark: Boolean, surface: Color, onAccent: Color): Color {
            fun ok(c: Color) =
                contrastRatio(c, surface) >= MIN_ON_SURFACE && contrastRatio(c, onAccent) >= MIN_TEXT
            val first = start.toColor()
            if (ok(first)) return first
            val step = if (dark) 0.02 else -0.02
            var best = first
            var l = start.l
            repeat(24) {
                l += step
                if (l < 0.05 || l > 0.98) return@repeat
                val candidate = start.copy(l = l).toColor()
                if (ok(candidate)) return candidate
                best = candidate
            }
            return best
        }
    }
}
