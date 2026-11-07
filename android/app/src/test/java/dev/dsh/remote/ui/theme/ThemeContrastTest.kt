package dev.dsh.remote.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配色契约测试。
 *
 * 这套界面最容易被后续改动弄坏的不是布局,是**对比度**:换一个配色、调一个明度,
 * 文字就可能掉到 4.5:1 以下,而截图上看不出来。所以把契约写成测试:
 *
 * - 六套配色 × 浅/深:主文字、次级、三级文字都要达标;
 * - 强调色上的文字 ≥ 4.5;
 * - 中性色换色相后明度不变(所以对比度跟着不变);
 * - 自定义色推导必须过 [AppAccent.guard];
 * - 玻璃兜底填充必须接近不透明(否则关掉透明后字会透出来)。
 */
class ThemeContrastTest {

    private val accents = AppAccent.presets

    @Test
    fun `body text meets 4_5 on page and cards in both schemes`() {
        for (accent in accents) {
            val n = NeutralTones.forAccent(accent)
            val lightSurfaces = listOf(n.mist, n.mistLowest, n.mistLow, n.mistContainer, n.mistHigh)
            for (bg in lightSurfaces) {
                assertAtLeast(4.5, n.ink, bg, "${accent.id}/light/ink on $bg")
                assertAtLeast(4.5, n.inkMuted, bg, "${accent.id}/light/inkMuted on $bg")
            }
            val darkSurfaces = listOf(n.night, n.nightLowest, n.nightLow, n.nightContainer, n.nightHigh)
            for (bg in darkSurfaces) {
                assertAtLeast(4.5, n.paper, bg, "${accent.id}/dark/paper on $bg")
                assertAtLeast(4.5, n.paperMuted, bg, "${accent.id}/dark/paperMuted on $bg")
            }
        }
    }

    @Test
    fun `tertiary text still carries information`() {
        // 三级文字(时间戳、摘要)按设计要 ≥ 4.5:它承载信息,不是装饰。
        for (accent in accents) {
            val n = NeutralTones.forAccent(accent)
            assertAtLeast(4.5, n.inkFaint, n.mist, "${accent.id}/light/tertiary")
            assertAtLeast(4.5, n.paperFaint, n.night, "${accent.id}/dark/tertiary")
        }
    }

    @Test
    fun `accent seeds carry readable labels`() {
        for (accent in accents) {
            assertAtLeast(4.5, accent.onSeed, accent.seed, "${accent.id}/light/onSeed")
            assertAtLeast(4.5, accent.onSeedDark, accent.seedDark, "${accent.id}/dark/onSeedDark")
            // 容器与其前景(容器上的字更小,要求 7)。
            assertAtLeast(7.0, accent.onContainer, accent.container, "${accent.id}/light/onContainer")
            assertAtLeast(7.0, accent.onContainerDark, accent.containerDark, "${accent.id}/dark/onContainerDark")
        }
    }

    @Test
    fun `accent is distinguishable from the page`() {
        for (accent in accents) {
            val n = NeutralTones.forAccent(accent)
            assertAtLeast(3.0, accent.seed, n.mistHighest, "${accent.id}/light/accent vs page")
            assertAtLeast(3.0, accent.seedDark, n.nightHighest, "${accent.id}/dark/accent vs page")
        }
    }

    @Test
    fun `user bubble text is readable`() {
        for (accent in accents) {
            assertAtLeast(4.5, accent.bubbleDark.let { accent.bubbleDark }, accent.bubble, "${accent.id}/bubble(fg=dark variant)")
            // 真实组合:浅色气泡上是 ink;深色气泡上是 paper。
            val n = NeutralTones.forAccent(accent)
            assertAtLeast(4.5, n.ink, accent.bubble, "${accent.id}/light/bubble text")
            assertAtLeast(4.5, n.paper, accent.bubbleDark, "${accent.id}/dark/bubble text")
        }
    }

    @Test
    fun `retint keeps luminance and therefore contrast`() {
        // 中性色换色相时明度必须不动,否则"换配色不改对比度"这句话就是假的。
        val n = NeutralTones.forAccent(AppAccent.amber)
        val base = NeutralTones.defaults
        val pairs = listOf(
            base.mist to n.mist,
            base.ink to n.ink,
            base.mistHighest to n.mistHighest,
            base.paper to n.paper,
            base.night to n.night,
        )
        for ((before, after) in pairs) {
            val l1 = Oklch.fromColor(before).l
            val l2 = Oklch.fromColor(after).l
            assertTrue(
                "luminance drifted: $before -> $after ($l1 vs $l2)",
                kotlin.math.abs(l1 - l2) < 0.02,
            )
        }
    }

    @Test
    fun `derived accent passes the guard`() {
        val picks = listOf(
            androidx.compose.ui.graphics.Color(0xFF4D6BFE), // DeepSeek 品牌蓝
            androidx.compose.ui.graphics.Color(0xFFFF0000), // 饱和红
            androidx.compose.ui.graphics.Color(0xFF00FF00), // 饱和绿
            androidx.compose.ui.graphics.Color(0xFFFFFF00), // 饱和黄
            androidx.compose.ui.graphics.Color(0xFF808080), // 中灰
            androidx.compose.ui.graphics.Color(0xFF101010), // 近黑
            androidx.compose.ui.graphics.Color(0xFFF0F0F0), // 近白
        )
        for (pick in picks) {
            val accent = AppAccent.derive(pick)
            val n = NeutralTones.forAccent(accent)
            // guard 的承诺:强调色对页面 ≥ 3,其上文字 ≥ 4.5。
            assertTrue(
                "derived accent $pick not separable from page",
                contrastRatio(accent.seed, n.mistHighest) >= AppAccent.MIN_ON_SURFACE - 0.01,
            )
            assertTrue(
                "derived accent $pick has unreadable label",
                contrastRatio(Color_White, accent.seed) >= AppAccent.MIN_TEXT - 0.01,
            )
        }
    }

    @Test
    fun `glass fallback fill is near opaque`() {
        // 关掉透明效果时走 fallbackFill:它必须接近不透明,否则背后的内容会透出来
        // 把字搅花(这正是参考项目里写明的那条约束)。
        for (accent in accents) {
            val light = DshSurfaces.light(accent, blurEnabled = false)
            val dark = DshSurfaces.dark(accent, blurEnabled = false)
            for ((name, style) in listOf(
                "light/chrome" to light.chrome,
                "light/sheet" to light.sheet,
                "light/card" to light.card,
                "dark/chrome" to dark.chrome,
                "dark/sheet" to dark.sheet,
                "dark/card" to dark.card,
            )) {
                assertTrue("$name fallback alpha too low: ${style.fallbackFill.alpha}", style.fallbackFill.alpha >= 0.6f)
            }
        }
    }

    @Test
    fun `semantic status colors are readable on their containers`() {
        for (dark in listOf(false, true)) {
            val sem = DshSemanticColors.forAccent(dark, AppAccent.mist)
            val pairs = listOf(
                "success" to (sem.onSuccess to sem.success),
                "warning" to (sem.onWarning to sem.warning),
                "danger" to (sem.onDanger to sem.danger),
                "successContainer" to (sem.onSuccessContainer to sem.successContainer),
                "warningContainer" to (sem.onWarningContainer to sem.warningContainer),
                "dangerContainer" to (sem.onDangerContainer to sem.dangerContainer),
                "infoContainer" to (sem.onInfoContainer to sem.infoContainer),
            )
            for ((name, pair) in pairs) {
                val (fg, bg) = pair
                assertAtLeast(4.5, fg, bg, "semantic $name (dark=$dark)")
            }
        }
    }

    @Test
    fun `ansi palette stays readable on the code well`() {
        for (dark in listOf(false, true)) {
            val sem = DshSemanticColors.forAccent(dark, AppAccent.mist)
            sem.ansi.forEachIndexed { index, color ->
                assertAtLeast(4.5, color, sem.codeWellBg, "ansi[$index] (dark=$dark)")
            }
        }
    }

    @Test
    fun `tool tones keep their foreground readable`() {
        for (dark in listOf(false, true)) {
            val sem = DshSemanticColors.forAccent(dark, AppAccent.mist)
            for (tone in ToolTone.entries) {
                val (bg, fg) = sem.tool(tone)
                assertAtLeast(4.5, fg, bg, "tool ${tone.name} (dark=$dark)")
            }
        }
    }

    @Test
    fun `identity colors pair up correctly`() {
        for (dark in listOf(false, true)) {
            val sem = DshSemanticColors.forAccent(dark, AppAccent.mist)
            assertEquals(sem.identity.size, sem.onIdentity.size)
            sem.identity.forEachIndexed { index, bg ->
                assertAtLeast(4.5, sem.onIdentity[index], bg, "identity[$index] (dark=$dark)")
            }
        }
    }

    @Test
    fun `oklch round trips`() {
        val colors = listOf(
            androidx.compose.ui.graphics.Color(0xFF4B67B4),
            androidx.compose.ui.graphics.Color(0xFF1E232E),
            androidx.compose.ui.graphics.Color(0xFFE6E9F0),
            androidx.compose.ui.graphics.Color(0xFFA54743),
        )
        for (color in colors) {
            val back = Oklch.fromColor(color).toColor()
            assertEquals("red ${color}->$back", color.red, back.red, 0.02f)
            assertEquals("green ${color}->$back", color.green, back.green, 0.02f)
            assertEquals("blue ${color}->$back", color.blue, back.blue, 0.02f)
        }
    }

    @Test
    fun `identity index is stable and in range`() {
        val keys = listOf("", "abc", "session-98e8", "D:\\dsh实验", "0f9c1d2e-aaaa-bbbb-cccc-1234567890ab")
        for (key in keys) {
            val a = DshSemanticColors.identityIndex(key)
            val b = DshSemanticColors.identityIndex(key)
            assertEquals("unstable for $key", a, b)
            assertTrue("out of range for $key: $a", a in 0..7)
        }
    }

    private fun assertAtLeast(min: Double, fg: androidx.compose.ui.graphics.Color, bg: androidx.compose.ui.graphics.Color, label: String) {
        val ratio = contrastRatio(fg, bg)
        assertTrue("$label: contrast %.2f < %.2f".format(ratio, min), ratio >= min - 0.01)
    }

    private val Color_White = androidx.compose.ui.graphics.Color.White
}
