package dev.dsh.remote.phone

/**
 * One step a typed instruction decomposes into.
 *
 * Deliberately a small closed set that maps 1:1 onto the privileged engine's
 * existing verbs (see [PhoneProto]); there is no "run this shell command" step
 * and no free-form path, so typing on this page can never escalate beyond what
 * the Settings switch already allows.
 */
sealed interface PhoneStep {
    /** Launch an app by (partial) package / label match. */
    data class Launch(val query: String) : PhoneStep

    data class Tap(val x: Int, val y: Int) : PhoneStep
    data class LongPress(val x: Int, val y: Int, val ms: Int = 600) : PhoneStep
    data class DoubleTap(val x: Int, val y: Int) : PhoneStep
    data class Swipe(val x: Int, val y: Int, val x2: Int, val y2: Int, val ms: Int = 260) : PhoneStep
    data class Scroll(val direction: String, val distance: Int = 5) : PhoneStep

    /** Global key: back / home / recents / notifications / enter / delete / power ... */
    data class Key(val name: String) : PhoneStep

    /** Physical key: wake / sleep / volume_up / volume_down / flashlight / mute ... */
    data class HardKey(val name: String) : PhoneStep

    data class TypeText(val text: String) : PhoneStep

    /** Absolute brightness 0..255, or relative delta when [delta] is set. */
    data class Brightness(val level: Int, val delta: Boolean = false) : PhoneStep
    data class Volume(val level: Int, val delta: Boolean = false, val stream: String = "music") : PhoneStep

    data class Toggle(val feature: String, val on: Boolean) : PhoneStep

    data class Wait(val ms: Long) : PhoneStep

    /** Something the parser could not map; surfaced to the user, never executed. */
    data class Unknown(val text: String, val hint: String) : PhoneStep
}

/**
 * Turns one line of typed instruction into executable [PhoneStep]s.
 *
 * The page this feeds is intentionally a plain text box, so the parser accepts
 * the handful of shapes a person actually types - Chinese or English, one per
 * line or separated by "然后 / 再 / ; / ，" - and refuses anything it is not
 * sure about rather than guessing (an unknown step is reported, not dropped).
 */
object PhoneCommand {

    /**
     * Clause separator.
     *
     * "再" is only a separator when it stands on its own ("再点…" / "然后再…"),
     * never inside a word: splitting on a bare 再 turned "输入 你好再见了" into
     * "输入 你好" + "见了" and silently ate the tail. "，"/"," only split when the
     * comma is followed by another verb-looking clause, which the caller
     * approximates by requiring a non-empty remainder.
     */
    private val SPLIT = Regex("(?:然后|接着|之后再|后再|再(?=点|按|滑|输|打|找|返|回|点)|;|；)")

    /** One keyed phrase -> a Key step. Matched as a whole token to avoid false hits. */
    private val KEY_WORDS: List<Pair<Regex, String>> = listOf(
        Regex("^(返回|后退|back)$") to "back",
        Regex("^(主页|桌面|回桌面|home)$") to "home",
        Regex("^(多任务|任务|最近|最近任务|recents)$") to "recents",
        Regex("^(通知栏|下拉通知|通知)$") to "notifications",
        Regex("^(确定|回车|enter)$") to "enter",
        Regex("^(删除|退格|backspace|delete)$") to "delete",
    )

    private val HARD_KEY_WORDS: List<Pair<Regex, String>> = listOf(
        Regex("^(亮屏|点亮屏幕|wake)$") to "wake",
        Regex("^(熄屏|锁屏|关屏|sleep)$") to "sleep",
        Regex("^(电源键|按电源|power)$") to "power",
        Regex("^(音量\\s*\\+|调大音量|音量大)$") to "volume_up",
        Regex("^(音量\\s*-|调小音量|音量小)$") to "volume_down",
        Regex("^(手电筒|手电|flashlight)$") to "flashlight",
        Regex("^(静音|mute)$") to "mute",
        Regex("^(播放|暂停|play|pause)$") to "play_pause",
    )

    private val SCROLL_WORDS: List<Pair<Regex, String>> = listOf(
        Regex("^(上滑|向上|上滚|往上|scroll\\s*up)$") to "up",
        Regex("^(下滑|向下|下滚|往下|scroll\\s*down)$") to "down",
        Regex("^(左滑|向左|左滚)$") to "left",
        Regex("^(右滑|向右|右滚)$") to "right",
    )

    private val TOGGLE_WORDS: List<Pair<Regex, String>> = listOf(
        Regex("^(wifi|无线|无线网|wi-?fi)") to "wifi",
        Regex("^(移动数据|流量|data)") to "data",
        Regex("^(飞行模式|飞行|airplane)") to "airplane",
        Regex("^(蓝牙|bluetooth)") to "bluetooth",
        Regex("^(定位|gps|location)") to "location",
        Regex("^(自动旋转|旋转锁|rotation)") to "rotation",
        Regex("^(夜间模式|深色|night)") to "night",
    )

    /**
     * Parse a whole input box. Each line / clause becomes one or more steps in
     * order; a clause that cannot be understood yields [PhoneStep.Unknown].
     */
    fun parse(input: String): List<PhoneStep> {
        val out = ArrayList<PhoneStep>()
        for (rawLine in input.split('\n')) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            for (clause in line.split(SPLIT)) {
                val piece = clause.trim()
                if (piece.isEmpty()) continue
                out.add(parseClause(piece))
            }
        }
        return out
    }

    private fun parseClause(clause: String): PhoneStep {
        // Trailing separators left over from splitting ("打开微信，" ) would
        // otherwise become part of the app name / typed text.
        val s = clause.trim().trimEnd('，', ',', '、').removeSuffix("。").removeSuffix(".").trim()
        val lower = s.lowercase()

        // "打开/启动/运行 <app>"
        Regex("^(?:帮我)?(?:打开|启动|运行|进入|open|launch|start)\\s*(?:一下)?\\s*(.+)$")
            .find(s)?.let { m ->
                val target = m.groupValues[1].trim()
                if (target.isNotEmpty()) return PhoneStep.Launch(target)
            }

        // "等待 2 秒" / "wait 500ms"
        Regex("^(?:等待|等一下|等|wait)\\s*(\\d+)\\s*(秒|s|毫秒|ms)?$")
            .find(lower)?.let { m ->
                val n = m.groupValues[1].toLongOrNull() ?: return@let
                val unit = m.groupValues[2]
                val ms = if (unit == "毫秒" || unit == "ms") n else n * 1000
                return PhoneStep.Wait(ms.coerceIn(0, 30_000))
            }

        // "点 500 800" / "点击 500,800" / "tap 500 800"
        Regex("^(?:点按|点击|点一下|点击坐标|点|tap|click)\\s*(\\d+)\\s*[,，\\s]\\s*(\\d+)$")
            .find(lower)?.let { m ->
                val x = m.groupValues[1].toIntOrNull()
                val y = m.groupValues[2].toIntOrNull()
                if (x != null && y != null) return PhoneStep.Tap(x, y)
            }

        // "长按 500 800"
        Regex("^(?:长按|按住|long\\s*press)\\s*(\\d+)\\s*[,，\\s]\\s*(\\d+)\\s*(?:(\\d+)\\s*(?:ms|毫秒))?$")
            .find(lower)?.let { m ->
                val x = m.groupValues[1].toIntOrNull()
                val y = m.groupValues[2].toIntOrNull()
                if (x != null && y != null) {
                    val ms = m.groupValues[3].toIntOrNull() ?: 600
                    return PhoneStep.LongPress(x, y, ms)
                }
            }

        // "双击 500 800"
        Regex("^(?:双击|double\\s*tap)\\s*(\\d+)\\s*[,，\\s]\\s*(\\d+)$")
            .find(lower)?.let { m ->
                val x = m.groupValues[1].toIntOrNull()
                val y = m.groupValues[2].toIntOrNull()
                if (x != null && y != null) return PhoneStep.DoubleTap(x, y)
            }

        // "滑动 100 800 100 200 300" / "swipe x y x2 y2 [ms]"
        Regex("^(?:滑动|拖拽|swipe|drag)\\s*(\\d+)\\s*[,，\\s]\\s*(\\d+)\\s*[,，\\s]\\s*(\\d+)\\s*[,，\\s]\\s*(\\d+)\\s*(?:(\\d+))?$")
            .find(lower)?.let { m ->
                val v = (1..4).map { m.groupValues[it].toIntOrNull() }
                if (v.all { it != null }) {
                    val ms = m.groupValues[5].toIntOrNull() ?: 260
                    return PhoneStep.Swipe(v[0]!!, v[1]!!, v[2]!!, v[3]!!, ms)
                }
            }

        // "输入 你好" / "type hello"
        Regex("^(?:输入文字|输入|打字|type)\\s*[:：]?\\s*(.+)$")
            .find(s)?.let { m ->
                val t = m.groupValues[1].trim()
                if (t.isNotEmpty()) return PhoneStep.TypeText(t)
            }

        // "亮度 120" / "亮度 +10" / "亮度 -10"
        Regex("^(?:亮度|brightness)\\s*([+-])?\\s*(\\d+)$").find(lower)?.let { m ->
            val n = m.groupValues[2].toIntOrNull() ?: return@let
            return if (m.groupValues[1].isNotEmpty()) {
                PhoneStep.Brightness(n, delta = true)
            } else {
                PhoneStep.Brightness(n.coerceIn(0, 255))
            }
        }

        // "音量 50" / "音量 +10"
        Regex("^(?:音量|volume)\\s*([+-])?\\s*(\\d+)$").find(lower)?.let { m ->
            val n = m.groupValues[2].toIntOrNull() ?: return@let
            return if (m.groupValues[1].isNotEmpty()) {
                PhoneStep.Volume(n, delta = true)
            } else {
                PhoneStep.Volume(n.coerceIn(0, 100))
            }
        }

        // Toggle: "打开 wifi" / "关闭蓝牙"
        Regex("^(打开|开启|开|关掉|关闭|关)\\s*(.+)$").find(s)?.let { m ->
            val on = m.groupValues[1] in setOf("打开", "开启", "开")
            val rest = m.groupValues[2].trim().lowercase()
            TOGGLE_WORDS.firstOrNull { (re, _) -> re.containsMatchIn(rest) }?.let { (_, feature) ->
                return PhoneStep.Toggle(feature, on)
            }
        }

        // Scroll words
        SCROLL_WORDS.firstOrNull { (re, _) -> re.containsMatchIn(lower) }?.let { (_, dir) ->
            return PhoneStep.Scroll(dir)
        }

        // Hard keys
        HARD_KEY_WORDS.firstOrNull { (re, _) -> re.matches(s) || re.matches(lower) }?.let { (_, name) ->
            return PhoneStep.HardKey(name)
        }

        // Global keys
        KEY_WORDS.firstOrNull { (re, _) -> re.matches(s) || re.matches(lower) }?.let { (_, name) ->
            return PhoneStep.Key(name)
        }

        return PhoneStep.Unknown(s, hint = unknownHint(s))
    }

    /** A short, concrete suggestion for a line the parser refused. */
    private fun unknownHint(text: String): String = when {
        text.any { it.isDigit() } ->
            "坐标类请写成：点 500 800 / 长按 500 800 / 滑动 100 800 100 200"
        text.isNotBlank() -> "没听懂。可写：打开微信 / 返回 / 点 500 800 / 输入 你好 / 亮度 120"
        else -> "空指令"
    }
}
