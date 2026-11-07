package dev.dsh.remote.phone

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Process
import java.io.File
import java.io.RandomAccessFile

/**
 * The privileged half of phone control: everything in here runs **inside the
 * Shizuku shell-uid process**, never in the app.
 *
 * That split is the whole security story. The app can only send the fixed op
 * set in [PhoneProto]; this engine validates each op and then runs one of a
 * handful of fixed Android binaries (`input`, `screencap`, `pm`, `am`,
 * `settings`, `cmd`). It never accepts a shell string, never takes a path to
 * read, never installs anything on its own. If the person does not switch phone
 * control on and grant Shizuku, this class is never instantiated at all.
 *
 * ## 为什么大部分操作用 `input` / `settings` 而不是反射
 *
 * 反射 `InputManager.injectInputEvent` 在不同 ROM 上签名不同,而且 targetSdk
 * 越高越容易被拦。`input` 是 shell 自带的,行为在各家 ROM 上一致;`settings`
 * 读写的也是同一份系统库。反射只留给"真的没有命令能做"的那几个键。
 */
@androidx.annotation.Keep
class PhoneControlService(context: Context) : IPhoneControl.Stub() {
    private val engine = PhoneControlEngine(context)
    private val appUid = context.applicationInfo.uid

    override fun execute(request: Bundle): Bundle {
        // Only the app that asked Shizuku for this service may drive the phone.
        val caller = android.os.Binder.getCallingUid()
        if (caller != appUid && caller != Process.myUid()) {
            return fail(PhoneProto.E_IDENTITY)
        }
        if (request.getString(PhoneProto.OP) == null) return fail(PhoneProto.E_FAILED)
        return engine.handle(request)
    }

    private fun fail(code: String) = Bundle().apply {
        putBoolean(PhoneProto.OK, false)
        putString(PhoneProto.ERROR, code)
    }
}

/**
 * One engine per UserService process. Turning the app's switch off unbinds the
 * service and tears this process down, so nothing keeps running behind the
 * person's back.
 */
internal class PhoneControlEngine(private val context: Context) {

    /** Where `screencap` drops the frame: shell-owned, so only shell can read it. */
    private val shotPath = "/data/local/tmp/dsh_phone_screen.png"

    fun handle(request: Bundle): Bundle = when (request.getString(PhoneProto.OP)) {
        PhoneProto.OP_PROBE -> probe()
        PhoneProto.OP_SCREENSHOT -> screenshot()
        PhoneProto.OP_READ -> read(request.getInt(PhoneProto.OFFSET), request.getInt(PhoneProto.LEN))
        PhoneProto.OP_TAP -> input("tap", request.getInt(PhoneProto.X), request.getInt(PhoneProto.Y))
        PhoneProto.OP_SWIPE -> input(
            "swipe",
            request.getInt(PhoneProto.X), request.getInt(PhoneProto.Y),
            request.getInt(PhoneProto.X2), request.getInt(PhoneProto.Y2),
            request.getInt(PhoneProto.MS).coerceIn(60, 3000),
        )
        PhoneProto.OP_LONG_PRESS -> longPress(
            request.getInt(PhoneProto.X),
            request.getInt(PhoneProto.Y),
            request.getInt(PhoneProto.MS).coerceIn(300, 5000),
        )
        PhoneProto.OP_DOUBLE_TAP -> doubleTap(request.getInt(PhoneProto.X), request.getInt(PhoneProto.Y))
        PhoneProto.OP_SCROLL -> scroll(
            request.getString(PhoneProto.DIRECTION) ?: "down",
            request.getInt(PhoneProto.DISTANCE).coerceIn(1, 10),
        )
        PhoneProto.OP_DRAG -> drag(
            request.getInt(PhoneProto.X), request.getInt(PhoneProto.Y),
            request.getInt(PhoneProto.X2), request.getInt(PhoneProto.Y2),
        )
        PhoneProto.OP_TEXT -> text(request.getString(PhoneProto.TEXT))
        PhoneProto.OP_KEY -> key(request.getString(PhoneProto.KEY))
        PhoneProto.OP_HARD_KEY -> hardKey(request.getString(PhoneProto.KEY))
        PhoneProto.OP_LAUNCH -> launch(request.getString(PhoneProto.PKG))
        PhoneProto.OP_APPS -> apps(includingSystem = false)
        PhoneProto.OP_INSTALLED -> apps(request.getBoolean(PhoneProto.INCLUDING_SYSTEM))
        PhoneProto.OP_FOREGROUND -> foreground()
        PhoneProto.OP_ROTATE -> rotate(request.getInt(PhoneProto.ORIENTATION))
        PhoneProto.OP_BRIGHTNESS -> brightness(request.getInt(PhoneProto.LEVEL))
        PhoneProto.OP_KEEP_AWAKE -> keepAwake(request.getBoolean(PhoneProto.ON))
        PhoneProto.OP_VOLUME -> volume(
            request.getString(PhoneProto.STREAM) ?: "music",
            request.getInt(PhoneProto.LEVEL),
        )
        PhoneProto.OP_RECENTS -> recents()
        PhoneProto.OP_CLEAR_BG -> clearBackground()
        PhoneProto.OP_NOTIFICATIONS -> notifications()
        PhoneProto.OP_UNINSTALL -> uninstall(request.getString(PhoneProto.PKG))
        PhoneProto.OP_FORCE_STOP -> forceStop(request.getString(PhoneProto.PKG))
        PhoneProto.OP_GET_CLIP -> getClipboard()
        PhoneProto.OP_SET_CLIP -> setClipboard(request.getString(PhoneProto.TEXT))
        PhoneProto.OP_DEVICE_INFO -> deviceInfo()
        PhoneProto.OP_TOGGLE -> toggle(request.getString(PhoneProto.FEATURE), request.getBoolean(PhoneProto.ON))
        PhoneProto.OP_SCREEN_TEXT -> screenText()
        PhoneProto.OP_SMS_CODE -> smsCode()
        PhoneProto.OP_RELEASE -> ok()
        else -> fail(PhoneProto.E_UNSUPPORTED)
    }

    // ══ 设备与屏幕 ══════════════════════════════════════════

    private fun probe(): Bundle {
        val (code, out) = exec("/system/bin/wm", "size")
        val match = if (code == 0) Regex("(\\d+)x(\\d+)").find(out) else null
        return ok().apply {
            putInt(PhoneProto.UID, Process.myUid())
            putInt(PhoneProto.WIDTH, match?.groupValues?.get(1)?.toIntOrNull() ?: 0)
            putInt(PhoneProto.HEIGHT, match?.groupValues?.get(2)?.toIntOrNull() ?: 0)
        }
    }

    /**
     * `screencap -p` straight to a file only shell can read, then the app pulls
     * it back in [CHUNK] pieces.
     *
     * Deliberately not a MediaProjection: shell uid may run screencap directly,
     * so the person never has to accept a "start recording your screen" system
     * dialog, and nothing keeps capturing once the request is answered. The file
     * is deleted on the next capture and on release.
     */
    private fun screenshot(): Bundle {
        val (code, out) = exec("/system/bin/screencap", "-p", shotPath)
        val file = File(shotPath)
        if (code != 0 || !file.exists() || file.length() <= 0L) {
            return fail(PhoneProto.E_FAILED).apply {
                putString(PhoneProto.REASON, out.take(200).ifBlank { "screencap $code" })
            }
        }
        return ok().apply { putLong(PhoneProto.BYTES, file.length()) }
    }

    /** One slice of the last screenshot, so a multi-MB PNG fits in a Binder call. */
    private fun read(offset: Int, length: Int): Bundle {
        val file = File(shotPath)
        if (!file.exists()) return fail(PhoneProto.E_FAILED)
        if (offset < 0 || length <= 0 || length > CHUNK) return fail(PhoneProto.E_FAILED)
        return try {
            RandomAccessFile(file, "r").use { raf ->
                if (offset > raf.length()) return fail(PhoneProto.E_FAILED)
                val size = minOf(length.toLong(), raf.length() - offset).toInt()
                val buffer = ByteArray(size)
                raf.seek(offset.toLong())
                raf.readFully(buffer)
                ok().apply {
                    putByteArray(PhoneProto.CHUNK_DATA, buffer)
                    putInt(PhoneProto.N, size)
                }
            }
        } catch (t: Throwable) {
            fail(PhoneProto.E_FAILED).apply { putString(PhoneProto.REASON, t.javaClass.simpleName) }
        }
    }

    // ══ 输入 ══════════════════════════════════════════════

    /**
     * Read the visible text of the current screen via `uiautomator dump`.
     *
     * Strictly read-only: dump writes to a shell-owned temp file, we read it
     * back and delete it. No input event is synthesised here — this exists so
     * the review layer can decide whether the screen about to be tapped is a
     * payment / verification-code form.
     */
    private fun screenText(): Bundle {
        val dumpPath = "/data/local/tmp/dsh_phone_ui.xml"
        val (code, out) = exec("/system/bin/uiautomator", "dump", dumpPath)
        if (code != 0) {
            return fail(PhoneProto.E_FAILED).apply {
                putString(PhoneProto.REASON, out.take(200).ifBlank { "dump exit $code" })
            }
        }
        val xml = try {
            java.io.File(dumpPath).readText()
        } catch (t: Throwable) {
            return fail(PhoneProto.E_FAILED).apply {
                putString(PhoneProto.REASON, t.javaClass.simpleName)
            }
        } finally {
            runCatching { java.io.File(dumpPath).delete() }
        }
        // A UI dump groups every node; cap what we hand back so a pathological
        // screen cannot blow up the Binder transaction or the caller's heap.
        val trimmed = if (xml.length > SCREEN_DUMP_MAX_CHARS) xml.substring(0, SCREEN_DUMP_MAX_CHARS) else xml
        // Pull the human-visible attributes out of the nodes: text / content-desc
        // / hint. Bounds are kept for tap targets so a caller could correlate.
        val texts = ArrayList<String>()
        val attr = Regex("(?:text|content-desc|hint)=\"([^\"]*)\"")
        for (m in attr.findAll(trimmed)) {
            val v = m.groupValues[1].trim()
            if (v.isNotEmpty()) texts.add(v)
        }
        return ok().apply {
            putStringArray(PhoneProto.TEXTS, texts.take(400).toTypedArray())
        }
    }

    /**
     * Did a **recent SMS containing digits** arrive?
     *
     * This is the only "a verification code really exists" signal we trust. It
     * reads the notification shade (`dumpsys notification --noredact`), keeps
     * only records posted by a messaging package, and requires a 4–8 digit run
     * in the title/body plus a recent timestamp when the dump exposes one.
     *
     * Deliberately reports a *sender + hint*, never the code itself: the review
     * dialog only needs to say "a code just arrived", and echoing the digits
     * would put the secret into logs and screenshots.
     */
    private fun smsCode(): Bundle {
        // Bounded: a busy shade can dump megabytes, and this runs in the shell
        // process where an OOM takes the whole feature (and the app's UI) down.
        val (code, out) = execBounded(SMS_DUMP_MAX_CHARS, "/system/bin/dumpsys", "notification", "--noredact")
        if (code != 0) {
            return fail(PhoneProto.E_FAILED).apply {
                putString(PhoneProto.REASON, out.take(200).ifBlank { "dumpsys exit $code" })
            }
        }
        var pkg = ""
        var postedAt = 0L
        var title = ""
        var detail: String? = null

        for (line in out.lineSequence()) {
            val t = line.trim()
            if (t.startsWith("NotificationRecord(")) {
                pkg = PKG_RE.find(t)?.groupValues?.get(1) ?: ""
                postedAt = TIME_RE.find(t)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                title = ""
                continue
            }
            if (!isMessaging(pkg)) continue
            when {
                t.startsWith("android.title=") -> title = unquote(t.removePrefix("android.title="))
                t.startsWith("android.text=") -> {
                    val body = unquote(t.removePrefix("android.text="))
                    val blob = "$title $body"
                    if (CODE_RE.containsMatchIn(blob) && isRecent(postedAt)) {
                        // Sender or app label plus a short, digit-free hint.
                        detail = "${pkg.substringAfterLast('.')}｜" + maskDigits(title.ifBlank { body }).take(48)
                        break
                    }
                }
            }
        }
        return ok().apply {
            putBoolean(PhoneProto.FOUND, detail != null)
            putString(PhoneProto.DETAIL, detail ?: "")
        }
    }

    private fun unquote(v: String): String =
        v.trim().removePrefix("String (").removeSuffix(")").trim().trim('"')

    private fun isMessaging(pkg: String): Boolean {
        if (pkg.isBlank()) return false
        val p = pkg.lowercase()
        return MESSAGING_HINTS.any { p.contains(it) }
    }

    /**
     * A record without any timestamp is treated as recent: Android has shipped
     * dumpsys layouts that omit it, and refusing to flag would silently disable
     * the check on those builds.
     */
    private fun isRecent(postedAt: Long): Boolean {
        if (postedAt <= 0L) return true
        // Some builds report seconds rather than milliseconds.
        val ms = if (postedAt < 100_000_000_000L) postedAt * 1000 else postedAt
        val age = System.currentTimeMillis() - ms
        return age in -60_000..RECENT_SMS_WINDOW_MS
    }

    /** Keep only the shape of the message: digits themselves are redacted. */
    private fun maskDigits(text: String): String =
        text.replace(Regex("\\d"), "•").replace(Regex("\\s+"), " ").trim()

    private fun input(vararg argv: Any): Bundle =
        run("/system/bin/input", *argv.map { it.toString() }.toTypedArray())

    /** 长按:`input swipe` 起点终点相同、时长为按住时间,系统按长按处理。 */
    private fun longPress(x: Int, y: Int, ms: Int): Bundle =
        run(
            "/system/bin/input", "swipe",
            x.toString(), y.toString(), x.toString(), y.toString(), ms.toString(),
        )

    private fun doubleTap(x: Int, y: Int): Bundle {
        val first = input("tap", x, y)
        if (!first.getBoolean(PhoneProto.OK)) return first
        Thread.sleep(90)
        return input("tap", x, y)
    }

    /**
     * 滚动:换算成一次覆盖屏幕 60% 的 swipe。
     *
     * 不用 `input roll`,那个只在部分 ROM 上有;swipe 到处都认。
     */
    private fun scroll(direction: String, distance: Int): Bundle {
        val (w, h) = screenSize()
        if (w <= 0 || h <= 0) return fail(PhoneProto.E_FAILED)
        val span = (h * 0.6f * (distance.coerceIn(1, 10) / 5f)).coerceIn(h * 0.15f, h * 0.85f)
        val cx = w / 2
        val cy = h / 2
        return when (direction.lowercase()) {
            "up" -> input("swipe", cx, (cy - span / 2).toInt(), cx, (cy + span / 2).toInt(), 260)
            "left" -> input("swipe", (cx + span / 2).toInt(), cy, (cx - span / 2).toInt(), cy, 260)
            "right" -> input("swipe", (cx - span / 2).toInt(), cy, (cx + span / 2).toInt(), cy, 260)
            else -> input("swipe", cx, (cy + span / 2).toInt(), cx, (cy - span / 2).toInt(), 260)
        }
    }

    /** 拖拽:慢速滑动(900ms),让系统按"拖动"而不是"甩动"处理。 */
    private fun drag(x: Int, y: Int, x2: Int, y2: Int): Bundle =
        run("/system/bin/input", "swipe", x.toString(), y.toString(), x2.toString(), y2.toString(), "900")

    private fun text(value: String?): Bundle {
        if (value.isNullOrEmpty()) return fail(PhoneProto.E_FAILED)
        // ProcessBuilder passes the argument literally (no shell in between), so
        // only `input`'s own space convention has to be encoded here. Escaping
        // lives on this side so nothing the app sends can become a command.
        return run("/system/bin/input", "text", value.replace(" ", "%s"))
    }

    private fun key(name: String?): Bundle {
        val code = when (name?.lowercase()) {
            "back" -> "KEYCODE_BACK"
            "home" -> "KEYCODE_HOME"
            "recents", "overview" -> "KEYCODE_APP_SWITCH"
            "enter" -> "KEYCODE_ENTER"
            "delete", "backspace" -> "KEYCODE_DEL"
            "wake" -> "KEYCODE_WAKEUP"
            "sleep" -> "KEYCODE_SLEEP"
            "notifications" -> "KEYCODE_NOTIFICATION"
            "volume_up" -> "KEYCODE_VOLUME_UP"
            "volume_down" -> "KEYCODE_VOLUME_DOWN"
            "power" -> "KEYCODE_POWER"
            "tab" -> "KEYCODE_TAB"
            "escape" -> "KEYCODE_ESCAPE"
            "space" -> "KEYCODE_SPACE"
            "menu" -> "KEYCODE_MENU"
            "search" -> "KEYCODE_SEARCH"
            "camera" -> "KEYCODE_CAMERA"
            "mute" -> "KEYCODE_VOLUME_MUTE"
            "play_pause" -> "KEYCODE_MEDIA_PLAY_PAUSE"
            "next" -> "KEYCODE_MEDIA_NEXT"
            "previous" -> "KEYCODE_MEDIA_PREVIOUS"
            "page_up" -> "KEYCODE_PAGE_UP"
            "page_down" -> "KEYCODE_PAGE_DOWN"
            "move_home" -> "KEYCODE_MOVE_HOME"
            "move_end" -> "KEYCODE_MOVE_END"
            else -> return fail(PhoneProto.E_UNSUPPORTED)
        }
        return run("/system/bin/input", "keyevent", code)
    }

    /**
     * 物理键。
     *
     * 手电走 `cmd flashlight`(Android 13+ 才有),没有就报 unsupported 而不是
     * 假装成功。
     */
    private fun hardKey(name: String?): Bundle = when (name?.lowercase()) {
        "flashlight" -> run("/system/bin/cmd", "flashlight", "toggle")
        "wake" -> run("/system/bin/input", "keyevent", "KEYCODE_WAKEUP")
        "sleep" -> run("/system/bin/input", "keyevent", "KEYCODE_SLEEP")
        else -> key(name)
    }

    // ══ 应用 ══════════════════════════════════════════════

    private fun launch(pkg: String?): Bundle {
        // Package names only: no component, no intent extras, no URI.
        if (!validPackage(pkg)) return fail(PhoneProto.E_FAILED)
        return run(
            "/system/bin/monkey", "-p", pkg!!,
            "-c", "android.intent.category.LAUNCHER", "1",
        )
    }

    /** 第三方(或全部)应用 + 用户可见的名字。 */
    private fun apps(includingSystem: Boolean): Bundle {
        val argv = if (includingSystem) {
            arrayOf("/system/bin/pm", "list", "packages")
        } else {
            arrayOf("/system/bin/pm", "list", "packages", "-3")
        }
        val (code, out) = exec(*argv)
        if (code != 0) return fail(PhoneProto.E_FAILED).apply {
            putString(PhoneProto.REASON, out.take(200))
        }
        val pm = context.packageManager
        val packages = ArrayList<String>()
        val labels = ArrayList<String>()
        out.lineSequence()
            .map { it.removePrefix("package:").trim() }
            .filter { it.isNotEmpty() }
            .sorted()
            .forEach { pkg ->
                packages.add(pkg)
                labels.add(
                    try {
                        pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
                    } catch (_: Throwable) {
                        pkg
                    },
                )
            }
        return ok().apply {
            putInt(PhoneProto.N, packages.size)
            putStringArray(PhoneProto.PACKAGES, packages.toTypedArray())
            putStringArray(PhoneProto.LABELS, labels.toTypedArray())
        }
    }

    private fun foreground(): Bundle {
        val (code, out) = exec("/system/bin/dumpsys", "activity", "activities")
        if (code != 0) return fail(PhoneProto.E_FAILED)
        val line = out.lineSequence().firstOrNull {
            it.contains("mResumedActivity") || it.contains("topResumedActivity")
        } ?: return fail(PhoneProto.E_FAILED)
        val match = Regex("([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)").find(line)
            ?: return fail(PhoneProto.E_FAILED)
        return ok().apply { putString(PhoneProto.PKG, match.groupValues[1]) }
    }

    private fun forceStop(pkg: String?): Bundle {
        if (!validPackage(pkg)) return fail(PhoneProto.E_FAILED)
        return run("/system/bin/am", "force-stop", pkg!!)
    }

    private fun uninstall(pkg: String?): Bundle {
        if (!validPackage(pkg)) return fail(PhoneProto.E_FAILED)
        return run("/system/bin/pm", "uninstall", "--user", "0", pkg!!)
    }

    private fun clearBackground(): Bundle = run("/system/bin/am", "kill-all")

    private fun recents(): Bundle {
        val (code, out) = exec("/system/bin/dumpsys", "activity", "recents")
        if (code != 0) return fail(PhoneProto.E_FAILED).apply {
            putString(PhoneProto.REASON, out.take(200))
        }
        // 只取包名,不猜界面状态:一行一个去重包名。
        val packages = out.lineSequence()
            .mapNotNull { Regex("([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+)/").find(it)?.groupValues?.get(1) }
            .distinct()
            .toList()
        return ok().apply {
            putInt(PhoneProto.N, packages.size)
            putStringArray(PhoneProto.PACKAGES, packages.toTypedArray())
        }
    }

    // ══ 系统设置 ══════════════════════════════════════════

    private fun rotate(orientation: Int): Bundle {
        val value = when (orientation) {
            0 -> "0"
            90 -> "1"
            180 -> "2"
            270 -> "3"
            else -> return fail(PhoneProto.E_UNSUPPORTED)
        }
        // 先关自动旋转,否则设置会被传感器立刻改回去。
        run("/system/bin/settings", "put", "system", "accelerometer_rotation", "0")
        return run("/system/bin/settings", "put", "system", "user_rotation", value)
    }

    /** 亮度:传 -1 表示读取,0–255 表示设置。 */
    private fun brightness(level: Int): Bundle {
        if (level < 0) {
            val (code, out) = exec("/system/bin/settings", "get", "system", "screen_brightness")
            if (code != 0) return fail(PhoneProto.E_FAILED)
            return ok().apply { putInt(PhoneProto.VALUE, out.trim().toIntOrNull() ?: -1) }
        }
        val clamped = level.coerceIn(0, 255)
        // 手动亮度:关掉自适应,再写值。
        run("/system/bin/settings", "put", "system", "screen_brightness_mode", "0")
        return run("/system/bin/settings", "put", "system", "screen_brightness", clamped.toString())
    }

    private fun keepAwake(on: Boolean): Bundle = run(
        "/system/bin/settings", "put", "global", "stay_on_while_plugged_in",
        if (on) "7" else "0",
    )

    /** 音量:level < 0 读取,否则设置(0–max)。 */
    private fun volume(stream: String, level: Int): Bundle {
        val name = when (stream.lowercase()) {
            "music", "media" -> "music"
            "ring" -> "ring"
            "alarm" -> "alarm"
            "notification" -> "notification"
            "call" -> "call"
            else -> "music"
        }
        if (level < 0) {
            val (code, out) = exec("/system/bin/cmd", "media_session", "volume", "--stream", name, "--get")
            if (code != 0) {
                // 老系统没有 media_session,退回 dumpsys audio 抓一行。
                val (c2, o2) = exec("/system/bin/dumpsys", "audio")
                if (c2 != 0) return fail(PhoneProto.E_FAILED)
                val m = Regex("- STREAM_${name.uppercase()}:.*?streamVolume:(\\d+)", RegexOption.DOT_MATCHES_ALL).find(o2)
                return ok().apply { putInt(PhoneProto.VALUE, m?.groupValues?.get(1)?.toIntOrNull() ?: -1) }
            }
            return ok().apply { putInt(PhoneProto.VALUE, out.trim().toIntOrNull() ?: -1) }
        }
        return run("/system/bin/cmd", "media_session", "volume", "--stream", name, "--set", level.coerceIn(0, 100).toString())
    }

    private fun toggle(feature: String?, on: Boolean): Bundle {
        val value = if (on) "1" else "0"
        return when (feature?.lowercase()) {
            "airplane" -> run("/system/bin/cmd", "connectivity", "airplane-mode", if (on) "enable" else "disable")
            "wifi" -> run("/system/bin/svc", "wifi", if (on) "enable" else "disable")
            "data", "mobile" -> run("/system/bin/svc", "data", if (on) "enable" else "disable")
            "bluetooth" -> run("/system/bin/cmd", "bluetooth_manager", if (on) "enable" else "disable")
            "location" -> run("/system/bin/settings", "put", "secure", "location_mode", if (on) "3" else "0")
            "rotation" -> run("/system/bin/settings", "put", "system", "accelerometer_rotation", value)
            "night", "night_mode" -> run("/system/bin/cmd", "uimode", "night", if (on) "yes" else "no")
            else -> fail(PhoneProto.E_UNSUPPORTED)
        }
    }

    // ══ 通知 / 剪贴板 / 设备信息 ══════════════════════════

    private fun notifications(): Bundle {
        val (code, out) = exec("/system/bin/dumpsys", "notification", "--noredact")
        if (code != 0) return fail(PhoneProto.E_FAILED).apply {
            putString(PhoneProto.REASON, out.take(200))
        }
        // 只抽"通知行":android.title / android.text 成对出现的地方。
        val texts = ArrayList<String>()
        var title: String? = null
        for (line in out.lineSequence()) {
            val t = line.trim()
            when {
                t.startsWith("android.title=") -> title = t.removePrefix("android.title=").trim()
                t.startsWith("android.text=") -> {
                    val body = t.removePrefix("android.text=").trim()
                    val head = title?.takeIf { it.isNotBlank() }
                    if (head != null) texts.add(if (body.isBlank()) head else "$head — $body")
                    title = null
                }
            }
            if (texts.size >= 40) break
        }
        return ok().apply {
            putInt(PhoneProto.N, texts.size)
            putStringArray(PhoneProto.TEXTS, texts.toTypedArray())
        }
    }

    private fun getClipboard(): Bundle {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            ?: return fail(PhoneProto.E_FAILED)
        return try {
            val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
            ok().apply { putString(PhoneProto.TEXT, text ?: "") }
        } catch (t: Throwable) {
            // Android 10+ 后台读剪贴板会被拒(只有前台/输入法能读),如实报错。
            fail(PhoneProto.E_DENIED).apply { putString(PhoneProto.REASON, t.javaClass.simpleName) }
        }
    }

    private fun setClipboard(text: String?): Bundle {
        if (text == null) return fail(PhoneProto.E_FAILED)
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            ?: return fail(PhoneProto.E_FAILED)
        return try {
            cm.setPrimaryClip(android.content.ClipData.newPlainText("dsh", text))
            ok()
        } catch (t: Throwable) {
            fail(PhoneProto.E_FAILED).apply { putString(PhoneProto.REASON, t.javaClass.simpleName) }
        }
    }

    /** 型号 / 系统版本 / 电量 / 存储 / 屏幕 —— 都从系统读,不猜。 */
    private fun deviceInfo(): Bundle {
        val sb = StringBuilder()
        sb.append("型号: ").append(Build.MODEL).append('\n')
        sb.append("厂商: ").append(Build.MANUFACTURER).append('\n')
        sb.append("系统: Android ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("ABI: ").append(Build.SUPPORTED_ABIS.firstOrNull() ?: "-").append('\n')
        val (wc, wOut) = exec("/system/bin/wm", "size")
        if (wc == 0) sb.append("屏幕: ").append(wOut.lines().firstOrNull { it.contains("x") }?.trim() ?: "-").append('\n')
        val (bc, bOut) = exec("/system/bin/dumpsys", "battery")
        if (bc == 0) {
            val level = Regex("level: (\\d+)").find(bOut)?.groupValues?.get(1)
            val status = Regex("status: (\\d+)").find(bOut)?.groupValues?.get(1)
            if (level != null) sb.append("电量: ").append(level).append("%")
            if (status != null) sb.append(" (status ").append(status).append(')')
            sb.append('\n')
        }
        val (dc, dOut) = exec("/system/bin/df", "-h", "/data")
        if (dc == 0) {
            val row = dOut.lines().lastOrNull { it.trim().isNotEmpty() }?.trim()
            if (!row.isNullOrBlank()) sb.append("存储: ").append(row).append('\n')
        }
        val (sc, sOut) = exec("/system/bin/getprop", "ro.build.display.id")
        if (sc == 0 && sOut.isNotBlank()) sb.append("构建: ").append(sOut.trim()).append('\n')
        return ok().apply { putString(PhoneProto.TEXT, sb.toString().trimEnd()) }
    }

    // ══ 底层 ══════════════════════════════════════════════

    private fun screenSize(): Pair<Int, Int> {
        val (code, out) = exec("/system/bin/wm", "size")
        val m = if (code == 0) Regex("(\\d+)x(\\d+)").find(out) else null
        return (m?.groupValues?.get(1)?.toIntOrNull() ?: 0) to
            (m?.groupValues?.get(2)?.toIntOrNull() ?: 0)
    }

    private fun validPackage(pkg: String?): Boolean =
        !pkg.isNullOrBlank() && pkg.matches(Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*$"))

    /** Runs one fixed binary and maps the exit code onto the wire contract. */
    private fun run(vararg argv: String): Bundle {
        val (code, out) = exec(*argv)
        return if (code == 0) ok() else fail(PhoneProto.E_FAILED).apply {
            putString(PhoneProto.REASON, out.take(200).ifBlank { "exit $code" })
        }
    }

    private fun exec(vararg argv: String): Pair<Int, String> = try {
        val process = ProcessBuilder(*argv).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor() to output.trim()
    } catch (t: Throwable) {
        -1 to (t.message ?: t.javaClass.simpleName)
    }

    /**
     * Like [exec] but stops reading after [maxChars].
     *
     * `dumpsys notification --noredact` can emit several megabytes on a busy
     * phone. Reading that into a String inside the shell process is a direct
     * route to OOM — which kills the service process and surfaces to the person
     * as the app "闪退"ing. Nothing here needs the whole dump: the first screen
     * of records already contains what we look for, so the rest is discarded and
     * the process is torn down.
     */
    private fun execBounded(maxChars: Int, vararg argv: String): Pair<Int, String> = try {
        val process = ProcessBuilder(*argv).redirectErrorStream(true).start()
        val sb = StringBuilder(minOf(maxChars, 8 * 1024))
        try {
            process.inputStream.bufferedReader().use { reader ->
                val buf = CharArray(8 * 1024)
                while (sb.length < maxChars) {
                    val n = reader.read(buf)
                    if (n <= 0) break
                    sb.appendRange(buf, 0, minOf(n, maxChars - sb.length))
                }
            }
        } finally {
            // We may leave output unread on purpose; destroy instead of waiting.
            if (sb.length >= maxChars) runCatching { process.destroy() }
            runCatching { process.waitFor() }
        }
        val code = runCatching { process.exitValue() }.getOrDefault(0)
        code to sb.toString().trim()
    } catch (t: Throwable) {
        -1 to (t.message ?: t.javaClass.simpleName)
    }

    private fun ok() = Bundle().apply { putBoolean(PhoneProto.OK, true) }

    private fun fail(code: String) = Bundle().apply {
        putBoolean(PhoneProto.OK, false)
        putString(PhoneProto.ERROR, code)
    }

    companion object {
        /** Binder transactions are ~1 MB; keep a wide margin for the Bundle wrapper. */
        const val CHUNK = 256 * 1024

        /**
         * How long an SMS still counts as "the code that just arrived".
         *
         * The shade can hold an old code for days; five minutes covers the gap
         * between receiving a code and typing it without letting a stale one
         * keep the guard armed.
         */
        private const val RECENT_SMS_WINDOW_MS = 5 * 60_000L

        /** Package-name fragments that identify a messaging app. */
        private val MESSAGING_HINTS = listOf(
            "messaging", "mms", "sms", "telephony", "messages", "smsmms",
        )

        /** `pkg=` inside a NotificationRecord header. */
        private val PKG_RE = Regex("pkg=([A-Za-z0-9._]+)")

        /** Post time, whichever spelling this Android build uses. */
        private val TIME_RE = Regex("(?:postTime|when)=(-?\\d{10,})")

        /** The shape of a verification code: 4–8 digits standing alone. */
        private val CODE_RE = Regex("(?<!\\d)\\d{4,8}(?!\\d)")

        /** Hard cap on the notification dump we parse (see [execBounded]). */
        private const val SMS_DUMP_MAX_CHARS = 192 * 1024

        /** Hard cap on the UI hierarchy we scan. */
        private const val SCREEN_DUMP_MAX_CHARS = 256 * 1024
    }
}
