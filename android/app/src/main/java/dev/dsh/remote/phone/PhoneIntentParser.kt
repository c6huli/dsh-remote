package dev.dsh.remote.phone

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Turns a free-form instruction into actions by **asking the model**, not by
 * matching a fixed phrase list.
 *
 * The planner sends one prompt to a dedicated dsh session and expects a strict
 * JSON array back. When the model is unreachable, slow, or answers with
 * something unparseable, [PhoneCommand.parse] (the deterministic fallback)
 * still handles the obvious shapes so the page never becomes unusable.
 */
object PhoneIntentParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The action vocabulary offered to the model. Kept in sync with [PhoneStep]. */
    private val VOCAB = """
        launch     {"op":"launch","app":"微信"}                 打开应用（app 可写中文名或包名）
        tap        {"op":"tap","x":500,"y":800}                 点按坐标
        longpress  {"op":"longpress","x":500,"y":800,"ms":600}  长按
        doubletap  {"op":"doubletap","x":500,"y":800}           双击
        swipe      {"op":"swipe","x":100,"y":800,"x2":100,"y2":200,"ms":260}
        scroll     {"op":"scroll","dir":"down"}                 dir: up/down/left/right
        key        {"op":"key","name":"back"}                   back/home/recents/notifications/enter/delete
        hardkey    {"op":"hardkey","name":"wake"}               wake/sleep/power/volume_up/volume_down/flashlight/mute/play_pause
        text       {"op":"text","text":"你好"}                   往当前输入框打字
        brightness {"op":"brightness","level":120}              0-255
        volume     {"op":"volume","level":50}                   0-100
        toggle     {"op":"toggle","feature":"wifi","on":true}   wifi/data/airplane/bluetooth/location/rotation/night
        wait       {"op":"wait","ms":800}
    """.trimIndent()

    /** System-style instruction wrapped around the user's sentence. */
    fun buildPrompt(userText: String, screenWidth: Int, screenHeight: Int): String = """
        你在把用户的一句话翻译成 Android 手机操作序列。
        屏幕分辨率是 ${screenWidth}x${screenHeight}（坐标用像素，含状态栏）。

        只能使用下面这些动作，不要发明新的：
        $VOCAB

        规则：
        1. 只输出一个 JSON 数组，不要任何解释、不要 markdown 代码块。
        2. 一个动作一个对象；按执行顺序排列。
        3. 用户没给具体坐标时，用合理估计（参照 ${screenWidth}x${screenHeight}），不要留空。
        4. 完全无法理解时输出 []。

        用户说：$userText
    """.trimIndent()

    /**
     * Parse the model's reply into steps. Tolerates code fences and prose around
     * the array; returns null when there is no usable JSON array.
     */
    fun parseReply(reply: String): List<PhoneStep>? {
        val candidate = extractJsonArray(reply) ?: return null
        val arr = runCatching { json.parseToJsonElement(candidate) as? JsonArray }.getOrNull() ?: return null
        if (arr.isEmpty()) return emptyList()
        val out = ArrayList<PhoneStep>(arr.size)
        for (el in arr) {
            val o = el as? JsonObject ?: continue
            stepOf(o)?.let { out.add(it) }
        }
        return out
    }

    private fun extractJsonArray(text: String): String? {
        val start = text.indexOf('[')
        if (start < 0) return null
        // Walk to the matching close bracket so trailing prose cannot break parsing.
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '[' -> depth++
                c == ']' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    private fun stepOf(o: JsonObject): PhoneStep? {
        val op = o.s("op").lowercase()
        return when (op) {
            "launch", "open", "start" -> o.s("app").takeIf { it.isNotBlank() }?.let { PhoneStep.Launch(it) }
            "tap", "click" -> {
                val x = o.i("x"); val y = o.i("y")
                if (x != null && y != null) PhoneStep.Tap(x, y) else null
            }
            "longpress", "long_press", "long" -> {
                val x = o.i("x"); val y = o.i("y")
                if (x != null && y != null) PhoneStep.LongPress(x, y, o.i("ms") ?: 600) else null
            }
            "doubletap", "double_tap", "double" -> {
                val x = o.i("x"); val y = o.i("y")
                if (x != null && y != null) PhoneStep.DoubleTap(x, y) else null
            }
            "swipe", "drag" -> {
                val x = o.i("x"); val y = o.i("y"); val x2 = o.i("x2"); val y2 = o.i("y2")
                if (x != null && y != null && x2 != null && y2 != null) {
                    PhoneStep.Swipe(x, y, x2, y2, o.i("ms") ?: 260)
                } else null
            }
            "scroll" -> {
                val d = o.s("dir").ifBlank { o.s("direction") }.lowercase()
                if (d in setOf("up", "down", "left", "right")) PhoneStep.Scroll(d) else null
            }
            "key" -> o.s("name").takeIf { it.isNotBlank() }?.let { PhoneStep.Key(it) }
            "hardkey", "hard_key" -> o.s("name").takeIf { it.isNotBlank() }?.let { PhoneStep.HardKey(it) }
            "text", "type", "input" -> o.s("text").let { if (it.isEmpty()) null else PhoneStep.TypeText(it) }
            "brightness" -> o.i("level")?.let { PhoneStep.Brightness(it.coerceIn(0, 255)) }
            "volume" -> o.i("level")?.let {
                PhoneStep.Volume(it.coerceIn(0, 100), stream = o.s("stream").ifBlank { "music" })
            }
            "toggle" -> {
                val f = o.s("feature")
                if (f.isBlank()) null else PhoneStep.Toggle(f, o.b("on") ?: true)
            }
            "wait", "sleep" -> PhoneStep.Wait((o.i("ms") ?: 500).toLong().coerceIn(0, 30_000))
            else -> null
        }
    }

    private fun JsonObject.s(key: String): String {
        // JSON null is a real member; treating it as the text "null" made an
        // app name become literally "null". Skip it properly.
        val p = this[key] as? JsonPrimitive ?: return ""
        if (p is kotlinx.serialization.json.JsonNull) return ""
        return runCatching { p.content }.getOrNull().orEmpty()
    }

    private fun JsonObject.i(key: String): Int? {
        val p = this[key] as? JsonPrimitive ?: return null
        if (p is kotlinx.serialization.json.JsonNull) return null
        return p.intOrNull ?: p.content.toDoubleOrNull()?.toInt()
    }

    private fun JsonObject.b(key: String): Boolean? {
        val p = this[key] as? JsonPrimitive ?: return null
        if (p is kotlinx.serialization.json.JsonNull) return null
        return p.booleanOrNull
    }
}
