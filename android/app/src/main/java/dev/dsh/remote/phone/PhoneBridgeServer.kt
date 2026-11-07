package dev.dsh.remote.phone

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * 让 **agent 操控手机** 的本地 HTTP 端点。
 *
 * ## 为什么是"本地 HTTP + 宿主插件"
 *
 * 模型跑在桌面的 dsh 里,它要动手机,链路必须是:
 *
 * ```
 * 模型 → phone 工具(宿主插件) → HTTP → 手机上的这个端点 → Shizuku shell 侧
 * ```
 *
 * 手机是 Shizuku 权限的持有者,桌面不可能直接拿到 Binder,所以端点开在手机
 * 这边、由宿主插件反向调用。这也是为什么它只监听 `127.0.0.1` 并带 token:
 * 端口只对同一台机器上的 adb reverse / 隧道可见,不暴露给局域网。
 *
 * ## 安全边界
 *
 * - 只监听 127.0.0.1,外部网络不可达;
 * - 必须带 `x-dsh-phone-token`,token 每次启动随机生成,写在一个只有本应用
 *   能读的文件里,由宿主插件读取;
 * - 设置里的总开关关掉 → 端点直接不启动;
 * - 端点本身**不含任何逻辑**,它只把固定动词转给 [PhoneControl];真正执行
 *   仍在 shell 侧,并且再校验一次。
 */
class PhoneBridgeServer(
    private val context: Context,
    private val control: PhoneControl,
    private val port: Int = DEFAULT_PORT,
) : NanoHTTPD("127.0.0.1", port) {

    companion object {
        private const val TAG = "PhoneBridge"

        /** 端点端口。与 App 的 3086(Web 代理)错开,避免抢占。 */
        const val DEFAULT_PORT = 3087

        /** token 落在应用私有目录,宿主插件通过 adb 读取同一个文件。 */
        fun tokenFile(context: Context): File = File(context.filesDir, "phone-bridge.token")

        fun readToken(context: Context): String? =
            tokenFile(context).takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }

        /**
         * 生成(或复用)token。首次调用写盘;之后每次启动复用同一个,
         * 免得宿主插件手里的旧 token 每次都要重新读。
         */
        fun ensureToken(context: Context): String {
            readToken(context)?.let { return it }
            val fresh = java.util.UUID.randomUUID().toString().replace("-", "")
            tokenFile(context).writeText(fresh)
            return fresh
        }
    }

    private val token: String = ensureToken(context)

    override fun serve(session: IHTTPSession): Response {
        // 只认本机回环来源。
        val remote = session.remoteIpAddress ?: ""
        if (!remote.startsWith("127.") && remote != "::1" && remote != "0:0:0:0:0:0:0:1") {
            return json(Response.Status.FORBIDDEN, JSONObject().put("error", "loopback only"))
        }
        if (session.headers["x-dsh-phone-token"] != token) {
            return json(Response.Status.UNAUTHORIZED, JSONObject().put("error", "bad token"))
        }
        val path = session.uri
        return try {
            when {
                path == "/phone/status" -> status()
                path == "/phone/act" -> act(session)
                path == "/phone/screen" -> screen()
                path == "/phone/apps" -> apps()
                else -> json(Response.Status.NOT_FOUND, JSONObject().put("error", "no such route"))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "phone bridge failed", t)
            json(
                Response.Status.INTERNAL_ERROR,
                JSONObject().put("ok", false).put("error", t.message ?: t.javaClass.simpleName),
            )
        }
    }

    /** 状态:授权是否就绪、屏幕尺寸、前台应用。 */
    private fun status(): Response {
        val st = control.status()
        return json(
            Response.Status.OK,
            JSONObject()
                .put("ok", true)
                .put("installed", st.installed)
                .put("running", st.running)
                .put("granted", st.granted)
                .put("connected", st.connected)
                .put("ready", st.ready)
                .put("width", st.width)
                .put("height", st.height)
                .put("reason", st.reason ?: JSONObject.NULL),
        )
    }

    /**
     * 执行一个动作。请求体是 JSON:
     * ```json
     * {"op":"tap","x":540,"y":1200}
     * {"op":"text","text":"你好"}
     * {"op":"launch","pkg":"com.tencent.mm"}
     * ```
     * 支持的动作见 [PhoneProto];不认识的 op 一律 400,不猜。
     */
    private fun act(session: IHTTPSession): Response {
        val body = readBody(session)
        val req = JSONObject(body.ifBlank { "{}" })
        val op = req.optString("op").lowercase()
        if (op.isBlank()) {
            return json(Response.Status.BAD_REQUEST, JSONObject().put("ok", false).put("error", "missing op"))
        }
        val result = runBlocking {
            try {
                // Safety gate for the agent channel.
                //
                // This endpoint has no UI, so it cannot "ask the person" the way
                // the in-app page does. It therefore refuses instead: an action
                // landing on a payment / verification / identity screen, or on a
                // screen whose text cannot be read, is rejected with 403 and the
                // reason, so the model must hand control back to the human.
                val review = reviewForAgent(op, req)
                if (review != null) return@runBlocking review
                when (op) {
                    "tap" -> { control.tap(req.optInt("x"), req.optInt("y")); ok() }
                    "double_tap" -> { control.doubleTap(req.optInt("x"), req.optInt("y")); ok() }
                    "long_press" -> {
                        control.longPress(req.optInt("x"), req.optInt("y"), req.optInt("ms", 600)); ok()
                    }
                    "swipe" -> {
                        control.swipe(
                            req.optInt("x"), req.optInt("y"),
                            req.optInt("x2"), req.optInt("y2"),
                            req.optInt("ms", 260),
                        ); ok()
                    }
                    "drag" -> {
                        control.drag(req.optInt("x"), req.optInt("y"), req.optInt("x2"), req.optInt("y2")); ok()
                    }
                    "scroll" -> {
                        control.scroll(req.optString("direction", "down"), req.optInt("distance", 5)); ok()
                    }
                    "text" -> { control.text(req.optString("text")); ok() }
                    "key" -> { control.key(req.optString("key")); ok() }
                    "hard_key" -> { control.hardKey(req.optString("key")); ok() }
                    "launch" -> { control.launchApp(req.optString("pkg")); ok() }
                    "force_stop" -> { control.forceStop(req.optString("pkg")); ok() }
                    "uninstall" -> { control.uninstall(req.optString("pkg")); ok() }
                    "rotate" -> { control.rotate(req.optInt("orientation")); ok() }
                    "brightness" -> ok().put("value", control.brightness(req.optInt("level", -1)))
                    "volume" -> ok().put(
                        "value",
                        control.volume(req.optString("stream", "music"), req.optInt("level", -1)),
                    )
                    "keep_awake" -> { control.keepAwake(req.optBoolean("on")); ok() }
                    "toggle" -> { control.toggle(req.optString("feature"), req.optBoolean("on")); ok() }
                    "clear_background" -> { control.clearBackground(); ok() }
                    "foreground" -> ok().put("pkg", control.foregroundPackage() ?: "")
                    "recents" -> ok().put("packages", JSONArray(control.recents()))
                    "notifications" -> ok().put("texts", JSONArray(control.notifications()))
                    "get_clipboard" -> ok().put("text", control.getClipboard())
                    "set_clipboard" -> { control.setClipboard(req.optString("text")); ok() }
                    "device_info" -> ok().put("text", control.deviceInfo())
                    else -> return@runBlocking JSONObject()
                        .put("ok", false)
                        .put("error", "unsupported op: $op")
                }
            } catch (t: Throwable) {
                JSONObject().put("ok", false).put("error", t.message ?: t.javaClass.simpleName)
            }
        }
        val code = when {
            result.optBoolean("ok") -> Response.Status.OK
            result.optBoolean("needsConfirmation") -> Response.Status.FORBIDDEN
            else -> Response.Status.BAD_REQUEST
        }
        return json(code, result)
    }

    /**
     * Risk review for the agent channel.
     *
     * Returns a rejection payload when the action must not run, otherwise null.
     * Only actions that can commit something are reviewed — navigation keys and
     * read-only queries pass straight through.
     */
    private fun reviewForAgent(op: String, req: JSONObject): JSONObject? {
        val committing = when (op) {
            "tap", "double_tap", "long_press", "swipe", "drag", "text" -> true
            else -> false
        }
        if (!committing) return null
        // Free-text ops can be dangerous by themselves (e.g. typing an ID number
        // into whatever field happens to be focused), so screen first.
        val texts = runBlocking {
            try {
                control.screenText()
            } catch (_: Throwable) {
                null
            }
        }
        // Verification is evidence-only: an SMS with digits, or a code-shaped
        // value being typed. A screen merely showing 验证码 no longer blocks.
        val typed = req.optString("text")
        val evidence = runBlocking {
            // Only a text op can consume a code; probing the shade for every tap
            // would add a dumpsys round trip to each coordinate click.
            val sms = if (op == "text") {
                try {
                    control.smsCode()
                } catch (_: Throwable) {
                    null
                }
            } else null
            PhoneRiskReview.VerifyEvidence(
                smsFound = sms?.found == true,
                smsDetail = sms?.detail.orEmpty(),
                secretTyped = op == "text" && PhoneRiskReview.looksLikeCode(typed),
            )
        }
        val unreadable = texts == null || texts.isEmpty()
        val verdict = PhoneRiskReview.reviewScreen(
            texts ?: emptyList(),
            readable = !unreadable,
            evidence = evidence,
        )
        val extra = if (op == "text") PhoneRiskReview.reviewInstruction(typed, emptyList(), evidence) else null
        val hits = (verdict.hits + (extra?.hits ?: emptyList())).distinctBy { it.category }
        val blocked = verdict.needsConfirmation || (extra?.needsConfirmation == true)
        if (!blocked) return null
        val reason = listOfNotNull(
            verdict.reason.takeIf { it.isNotBlank() },
            extra?.reason?.takeIf { it.isNotBlank() },
        ).joinToString("；")
        return JSONObject()
            .put("ok", false)
            .put("needsConfirmation", true)
            .put("error", "危险操作需要人工确认，agent 通道不能执行：$reason")
            .put("reason", reason)
            .put("categories", JSONArray(hits.map { it.category.label }))
    }

    /** 截一帧,base64 返回(PNG)。 */
    private fun screen(): Response {
        val bitmap = runBlocking { control.screenshot() }
            ?: return json(Response.Status.BAD_REQUEST, JSONObject().put("ok", false).put("error", "capture failed"))
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        val b64 = android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        return json(
            Response.Status.OK,
            JSONObject()
                .put("ok", true)
                .put("width", bitmap.width)
                .put("height", bitmap.height)
                .put("png", b64),
        )
    }

    /** 已安装应用(第三方),给模型挑包名用。 */
    private fun apps(): Response {
        val list = runBlocking { control.apps() }
        val arr = JSONArray()
        for (a in list) arr.put(JSONObject().put("label", a.label).put("pkg", a.pkg))
        return json(Response.Status.OK, JSONObject().put("ok", true).put("apps", arr))
    }

    private fun ok() = JSONObject().put("ok", true)

    private fun readBody(session: IHTTPSession): String = try {
        val files = HashMap<String, String>()
        session.parseBody(files)
        files["postData"] ?: ""
    } catch (_: Throwable) {
        ""
    }

    private fun json(status: Response.Status, body: JSONObject): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", body.toString())
}
