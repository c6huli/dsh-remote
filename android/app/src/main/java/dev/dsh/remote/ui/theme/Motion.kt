package dev.dsh.remote.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 形状词汇:**五档 + 胶囊**,全部连续曲率。
 *
 * 相邻两档之间约 1.4 倍,肉眼分得开,且每档用途固定:
 *
 * | 档 | 半径 | 用途 |
 * |---|---|---|
 * | xs | 6 | 行内代码、角标、进度条端头 |
 * | sm | 10 | Chip、工具图标块、小按钮、菜单项高亮 |
 * | md | 14 | 卡片、代码底、输入框、菜单、主按钮 |
 * | lg | 20 | 用户气泡、审批卡、设置分组卡 |
 * | xl | 28 | 输入条(composer)、底部弹层、对话框 |
 * | pill | — | 顶栏胶囊、模型胶囊、发送键、思考胶囊 |
 *
 * DSH 官方 Web 的阶梯是 4/8/12/16/20/28(panel = 28),这里在触屏上整体放大一点,
 * 但保留 28 作为最外层容器半径,与官方 composer 一致。
 */
object AppShape {
    val xs: Dp = 6.dp
    val sm: Dp = 10.dp
    val md: Dp = 14.dp
    val lg: Dp = 20.dp
    val xl: Dp = 28.dp
}

/** 间距阶梯(4 的倍数为主,2/6 用于紧凑行内)。 */
object AppSpace {
    val xxs: Dp = 2.dp
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 20.dp
    val xxl: Dp = 24.dp
    val xxxl: Dp = 32.dp

    /** 页面水平留白。 */
    val gutter: Dp = 16.dp

    /** 同一轮内部(工具行 → 正文)的竖向间距。 */
    val inTurn: Dp = 8.dp

    /** 换轮(上一轮回答 → 下一条用户消息)的竖向间距。 */
    val betweenTurns: Dp = 24.dp

    /** 正文最大阅读宽度;平板 / 横屏 / 折叠屏展开时居中。与官方 748px 同档。 */
    val readingWidth: Dp = 720.dp

    /** 浮在阅读列上的控件离阅读列两边的距离。 */
    val floatInset: Dp = 12.dp

    /** 那些控件的最大宽度(阅读列减两边 [floatInset])。 */
    val floatingWidth: Dp = readingWidth - floatInset * 2
}

/**
 * 动效 token。原则:**安静、短、跟手**。
 *
 * - 反馈类:按压、图标切换 ≤ 160ms,快到"感觉不到在等";
 * - 结构类:折叠、胶囊变形、抽屉 240–340ms,慢到"看得出从哪来";
 * - 环境类:呼吸 2.4s 一个周期,慢到"不打扰"。
 *
 * 系统开启"移除动画"时,[of] 取时长会得到 0;循环动画用 [reduced] 判断后停在静止帧。
 */
object AppMotion {
    /** 按压缩放、水波起始。 */
    const val pressMs = 90

    /** 淡入淡出、图标交叉切换、复制成功对勾。 */
    const val quickMs = 160

    /** 小块展开收起、列表项进出。 */
    const val standardMs = 240

    /** 思考块 / 工具行的折叠展开(大块高度变化,慢一拍才不"咔"一下)。 */
    const val collapseMs = 280

    /** 胶囊变形(顶栏展开、模型选择)、抽屉落位。 */
    const val emphasizedMs = 340

    /** 底部弹层进 / 出。 */
    const val sheetInMs = 380
    const val sheetOutMs = 260

    /** 页面级过渡、安装步骤切换。 */
    const val slowMs = 480

    /** 环境呼吸(连接中的状态点)。 */
    const val breathMs = 2400

    /** 标准缓动(M3 standard,与 DSH Web 的 cubic-bezier(.4,0,.2,1) 同族)。 */
    val std: Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

    /** 进场:快出慢停。 */
    val enter: Easing = LinearOutSlowInEasing

    /** 退场:慢起快走。 */
    val exit: Easing = FastOutLinearInEasing

    /** 折叠展开。 */
    val collapseCurve: Easing = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)

    /** 滑行:抽屉、胶囊位移这类"跟手后落位"的运动,iOS 式长尾减速。 */
    val glide: Easing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)

    /** 首次出现的列表逐项错开(≤ 30ms,只错开前 [staggerLimit] 项)。 */
    const val staggerMs = 30
    const val staggerLimit = 8

    /** 首次出现时上移的距离(逻辑像素)。 */
    const val risePx = 8f

    /** 按压缩放:按下 [pressMs] 缩到 [pressScale],松手 [releaseMs] 回到 1(不越冲)。 */
    const val pressScale = 0.97f
    const val releaseMs = 200

    /** 弹层升起:快出、长尾减速,像有一点阻尼、不回弹。 */
    val sheetRise: Easing = CubicBezierEasing(0.2f, 0.9f, 0.25f, 1f)

    /** 弹层收起(作为 reverse 曲线:反向播放时先慢后快)。 */
    val sheetFall: Easing = CubicBezierEasing(0.33f, 1f, 0.68f, 1f)
}

/**
 * 语义色:与强调色**无关**的固定色。
 *
 * 只有中性的那几格(文字层级、代码井)随配色换色调,见 [DshSemanticColors.forAccent]。
 *
 * - 一张 `bash` 行在任何配色下都必须长得一样 —— 色相本身就是语义。
 * - 所有 mono / 代码 / diff 必须铺在 [codeWellBg] 上,**不能**直接放在玻璃或页面
 *   渐变上:只有固定的底才能保证这些固定前景色的对比度。
 * - 文字类前景对其底色 ≥ 4.5(容器上的 on-色 ≥ 7)。
 */
data class DshSemanticColors(
    /** 三级文字(时间戳、摘要、占位符)。仍 ≥ 4.5,可承载信息。 */
    val textTertiary: Color,
    /** 禁用 / 装饰。不得承载必要信息。 */
    val textDisabled: Color,
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val danger: Color,
    val onDanger: Color,
    val dangerContainer: Color,
    val onDangerContainer: Color,
    val info: Color,
    val infoContainer: Color,
    val onInfoContainer: Color,
    val diffAddBg: Color,
    val diffAddFg: Color,
    val diffDelBg: Color,
    val diffDelFg: Color,
    val diffHunkFg: Color,
    val codeWellBg: Color,
    val codeWellHeader: Color,
    val codeWellFg: Color,
    val codeWellBorder: Color,
    val lineNumberFg: Color,
    /** SGR 30–37 + 90–97,对 [codeWellBg] 全部 ≥ 4.5。 */
    val ansi: List<Color>,
    /** 身份色(会话头像、设置分组图标)。容器 / on-容器,固定不随 accent 转。 */
    val identity: List<Color>,
    val onIdentity: List<Color>,
    /** 工具类别色,按 [ToolTone] 顺序取。 */
    val toolContainers: List<Color>,
    val onToolContainers: List<Color>,
) {
    fun tool(tone: ToolTone): Pair<Color, Color> =
        toolContainers[tone.ordinal] to onToolContainers[tone.ordinal]

    companion object {
        /**
         * [accent] 配色下的一套语义色。ANSI、身份色、工具色原样不动(色相本身就是
         * 语义);中性那几格 —— 三级 / 禁用文字、代码底、行号、diff 标题 —— 换成这套
         * 配色的中性色。雾蓝直接返回 [light] / [dark]。
         */
        fun forAccent(dark: Boolean, accent: AppAccent): DshSemanticColors {
            val base = if (dark) dark() else light()
            val n = NeutralTones.forAccent(accent)
            if (n === NeutralTones.defaults) return base
            return if (dark) {
                base.copy(
                    textTertiary = n.paperFaint,
                    textDisabled = n.paperGhost,
                    diffHunkFg = n.paperMuted,
                    codeWellBg = n.codeWellDark,
                    codeWellHeader = n.codeWellHeaderDark,
                    codeWellFg = n.paper,
                    codeWellBorder = n.codeWellBorderDark,
                    lineNumberFg = n.lineNumberDark,
                )
            } else {
                base.copy(
                    textTertiary = n.inkFaint,
                    textDisabled = n.inkGhost,
                    diffHunkFg = n.inkMuted,
                    codeWellBg = n.codeWell,
                    codeWellHeader = n.codeWellHeader,
                    codeWellFg = n.ink,
                    codeWellBorder = n.codeWellBorder,
                    lineNumberFg = n.lineNumber,
                )
            }
        }

        /** 由稳定字符串(会话 id、工作目录)映射到 identity 下标。 */
        fun identityIndex(key: String, slots: Int = 8): Int {
            var hash = -0x7ee3623b // 0x811c9dc5 as signed int
            for (unit in key) {
                hash = hash xor unit.code
                hash *= 0x01000193
            }
            val positive = hash.toLong() and 0xFFFFFFFFL
            return (positive % slots).toInt()
        }

        fun light() = DshSemanticColors(
            textTertiary = Color(0xFF646974),
            textDisabled = Color(0xFFA1A5AC),
            // 状态色一律低饱和:鲜绿 / 鲜红一出现,界面立刻变成"运维面板"。
            success = Color(0xFF337250),
            onSuccess = Color(0xFFF7FBF9),
            successContainer = Color(0xFFD9F0E1),
            onSuccessContainer = Color(0xFF153F29),
            warning = Color(0xFF936123),
            onWarning = Color(0xFFFCFAF6),
            warningContainer = Color(0xFFF9E9CE),
            onWarningContainer = Color(0xFF55350D),
            danger = Color(0xFFA54743),
            onDanger = Color(0xFFFDF9F8),
            dangerContainer = Color(0xFFFFE2E0),
            onDangerContainer = Color(0xFF642724),
            info = Color(0xFF2971A7),
            infoContainer = Color(0xFFDBECFC),
            onInfoContainer = Color(0xFF123B5A),
            diffAddBg = Color(0xFFDCF4E3),
            diffAddFg = Color(0xFF1F613C),
            diffDelBg = Color(0xFFFFE4E2),
            diffDelFg = Color(0xFF923A37),
            diffHunkFg = Color(0xFF4F5562),
            codeWellBg = Color(0xFFEDF0F6),
            codeWellHeader = Color(0xFFE7EBF3),
            codeWellFg = Color(0xFF1E232E),
            codeWellBorder = Color(0xFFDBDEE5),
            lineNumberFg = Color(0xFF7D8088),
            // 浅色井底上的 ANSI:"白"必须映射成深灰,否则在浅底上看不见。
            ansi = listOf(
                Color(0xFF1E232E), Color(0xFF903C39), Color(0xFF1B6C36), Color(0xFF775300),
                Color(0xFF2E5A9A), Color(0xFF774382), Color(0xFF006770), Color(0xFF494D55),
                Color(0xFF666970), Color(0xFFA14B46), Color(0xFF2D7B44), Color(0xFF876114),
                Color(0xFF3C69AA), Color(0xFF865192), Color(0xFF007781), Color(0xFF2A2E36),
            ),
            identity = listOf(
                Color(0xFFDAECFF), Color(0xFFE9E6FF), Color(0xFFF9E1F3), Color(0xFFFFE2E0),
                Color(0xFFF8E6D1), Color(0xFFE0EFD8), Color(0xFFD3F1E9), Color(0xFFD1F0F8),
            ),
            onIdentity = listOf(
                Color(0xFF173F65), Color(0xFF3E3463), Color(0xFF562B4E), Color(0xFF602A2A),
                Color(0xFF563500), Color(0xFF2A4618), Color(0xFF00483D), Color(0xFF004552),
            ),
            // 顺序 = ToolTone:terminal, read, edit, search, web, agent, plan, neutral
            toolContainers = listOf(
                Color(0xFFE6EAF2), Color(0xFFDAECFF), Color(0xFFFAE6CD), Color(0xFFEDE5FE),
                Color(0xFFD7F1E2), Color(0xFFF9E2F0), Color(0xFFD0F1F0), Color(0xFFE7E9EF),
            ),
            onToolContainers = listOf(
                Color(0xFF343D51), Color(0xFF173F65), Color(0xFF543600), Color(0xFF443261),
                Color(0xFF034A2E), Color(0xFF582B4A), Color(0xFF004747), Color(0xFF363D4C),
            ),
        )

        fun dark() = DshSemanticColors(
            textTertiary = Color(0xFF9A9EA9),
            textDisabled = Color(0xFF656971),
            success = Color(0xFF8BCCA6),
            onSuccess = Color(0xFF0C2518),
            successContainer = Color(0xFF233F2F),
            onSuccessContainer = Color(0xFFC9E7D4),
            warning = Color(0xFFE6BD7C),
            onWarning = Color(0xFF2F1E06),
            warningContainer = Color(0xFF4C371D),
            onWarningContainer = Color(0xFFF4E2C3),
            danger = Color(0xFFE69089),
            onDanger = Color(0xFF331513),
            dangerContainer = Color(0xFF542C29),
            onDangerContainer = Color(0xFFF8D9D7),
            info = Color(0xFF8CBDE8),
            infoContainer = Color(0xFF253C4F),
            onInfoContainer = Color(0xFFD1E4F4),
            diffAddBg = Color(0xFF1C3424),
            diffAddFg = Color(0xFF98D5AC),
            diffDelBg = Color(0xFF422423),
            diffDelFg = Color(0xFFF2A7A1),
            diffHunkFg = Color(0xFFB6BBC5),
            // 夜里的底比页面更深:代码像嵌进纸里,而不是浮在上面。
            codeWellBg = Color(0xFF14171E),
            codeWellHeader = Color(0xFF1A1E26),
            codeWellFg = Color(0xFFE6E9F0),
            codeWellBorder = Color(0xFF2F333A),
            lineNumberFg = Color(0xFF71747D),
            ansi = listOf(
                Color(0xFF7C8089), Color(0xFFDE958E), Color(0xFF81BB8D), Color(0xFFC9A567),
                Color(0xFF87ADE4), Color(0xFFC499CE), Color(0xFF5DBBC6), Color(0xFFCED1D8),
                Color(0xFF9A9EA7), Color(0xFFF9AEA7), Color(0xFF9AD5A5), Color(0xFFE3BE80),
                Color(0xFFA0C6FF), Color(0xFFDEB2E9), Color(0xFF78D5E0), Color(0xFFF0F2F6),
            ),
            identity = listOf(
                Color(0xFF243A50), Color(0xFF38334F), Color(0xFF472E42), Color(0xFF4E2E2D),
                Color(0xFF47341A), Color(0xFF2D3E24), Color(0xFF174038), Color(0xFF153E47),
            ),
            onIdentity = listOf(
                Color(0xFFC5DEF8), Color(0xFFDBD6F7), Color(0xFFEFD0E7), Color(0xFFF7D0CD),
                Color(0xFFEED7BB), Color(0xFFCEE2C4), Color(0xFFBCE5DB), Color(0xFFBAE3ED),
            ),
            toolContainers = listOf(
                Color(0xFF303540), Color(0xFF21374E), Color(0xFF443116), Color(0xFF39304B),
                Color(0xFF1D3D2D), Color(0xFF452C3D), Color(0xFF0F3D3D), Color(0xFF32353D),
            ),
            onToolContainers = listOf(
                Color(0xFFD1D8E5), Color(0xFFC0DBF8), Color(0xFFEBD3B4), Color(0xFFDCD1F3),
                Color(0xFFBDE2CD), Color(0xFFEDCCE1), Color(0xFFB3E2E1), Color(0xFFD3D7E1),
            ),
        )
    }
}

/** DSH 工具调用的视觉类别。名字 → 类别的映射在 [dev.dsh.remote.ui.ToolKind]。 */
enum class ToolTone {
    /** bash / pwsh / job_*:终端。 */
    Terminal,

    /** read / read_image / present:读取。 */
    Read,

    /** write / edit / str_replace_editor:改动文件。 */
    Edit,

    /** grep / glob:搜索。 */
    Search,

    /** web_fetch / web_search:联网。 */
    Web,

    /** subagent / workflow / skill / send_message:另一个智能体在干活。 */
    Agent,

    /** todo_write / *_goal / ask_user_question:计划与协作。 */
    Plan,

    /** MCP 工具、插件工具、未知工具。 */
    Neutral,
}
