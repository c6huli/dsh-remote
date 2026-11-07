package dev.dsh.remote.data

import dev.dsh.remote.net.DshJson
import dev.dsh.remote.net.RpcClient
import java.io.IOException
import java.net.URLEncoder
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class DshApi(private val rpc: RpcClient) {
    private val json = DshJson.json

    suspend fun workspaceList(): WorkspaceListValue {
        val v = rpc.invoke("workspace.list", JsonObject(emptyMap()))
        return json.decodeFromJsonElement(WorkspaceListValue.serializer(), v)
    }

    suspend fun sessionList(): List<SessionSummary> {
        val v = rpc.invoke("session.list", JsonObject(emptyMap()))
        return json.decodeFromJsonElement(SessionListValue.serializer(), v).items
    }

    suspend fun sessionHistory(
        sessionId: String,
        beforeSeq: Long? = null,
        maxMessages: Int? = null,
    ): SessionHistoryValue {
        val payload = buildJsonObject {
            put("sessionId", sessionId)
            if (beforeSeq != null) put("beforeSeq", beforeSeq)
            if (maxMessages != null) put("maxMessages", maxMessages)
        }
        return json.decodeFromJsonElement(SessionHistoryValue.serializer(), rpc.invoke("session.history", payload))
    }

    /**
     * Read one durable image attachment's bytes for a session (base64 in the
     * RPC value). The host only serves attachments this session's log references,
     * so the correct sessionId must be passed. Returns null when data is absent.
     */
    suspend fun sessionAttachment(sessionId: String, attachmentId: String): AttachmentImage? {
        val payload = buildJsonObject {
            put("sessionId", sessionId)
            put("attachmentId", attachmentId)
        }
        val v = rpc.invoke("session.attachment", payload) as? JsonObject ?: return null
        val data = v["data"]?.jsonPrimitive?.content ?: return null
        val att = v["attachment"]?.jsonObject
        return AttachmentImage(
            attachmentId = attachmentId,
            mediaType = att?.get("mediaType")?.jsonPrimitive?.content ?: "image/png",
            width = att?.get("width")?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            height = att?.get("height")?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            base64 = data,
        )
    }

    suspend fun sessionCreate(
        workspaceId: String? = null,
        agentPreset: String? = null,
        sessionId: String? = null,
    ): SessionCreateValue {
        val payload = buildJsonObject {
            if (workspaceId != null) put("workspaceId", workspaceId)
            if (agentPreset != null) put("agentPreset", agentPreset)
            if (sessionId != null) put("sessionId", sessionId)
        }
        return json.decodeFromJsonElement(SessionCreateValue.serializer(), rpc.invoke("session.create", payload))
    }

    suspend fun sessionPrompt(sessionId: String, text: String) {
        val payload = buildJsonObject {
            put("sessionId", sessionId)
            put("mode", "queue")
            putJsonArray("content") {
                addJsonObject {
                    put("type", "text")
                    put("text", text)
                }
            }
        }
        rpc.invoke("session.prompt", payload)
    }

    /**
     * Edit/remove an item already sitting in the host's native inbox queue.
     * actionKind: "edit" (with text content) | "remove" | "steer".
     */
    suspend fun sessionUpdateQueue(sessionId: String, itemId: String, actionKind: String, text: String? = null) {
        val payload = buildJsonObject {
            put("sessionId", sessionId)
            put("itemId", itemId)
            putJsonObject("action") {
                put("kind", actionKind)
                if (actionKind == "edit") {
                    putJsonArray("content") {
                        addJsonObject { put("type", "text"); put("text", text ?: "") }
                    }
                }
            }
        }
        rpc.invoke("session.updateQueue", payload)
    }

    /** Fork the conversation into a new child session, anchored at the given event seq. */
    suspend fun sessionFork(sessionId: String, atSeq: Long? = null): SessionCreateValue {
        val payload = buildJsonObject {
            put("sessionId", sessionId)
            if (atSeq != null) put("atSeq", atSeq)
        }
        return json.decodeFromJsonElement(SessionCreateValue.serializer(), rpc.invoke("session.fork", payload))
    }

    /** Send an image as a user prompt (base64 data). */
    suspend fun sessionPromptImage(sessionId: String, mediaType: String, base64: String, name: String?) {
        val payload = buildJsonObject {
            put("sessionId", sessionId)
            put("mode", "queue")
            putJsonArray("content") {
                addJsonObject {
                    put("type", "image")
                    put("mediaType", mediaType)
                    put("data", base64)
                    name?.let { put("name", it) }
                }
            }
        }
        rpc.invoke("session.prompt", payload)
    }

    /** Send an image + optional text as one user prompt. */
    suspend fun sessionPromptImageText(sessionId: String, mediaType: String, base64: String, name: String?, text: String) {
        val payload = buildJsonObject {
            put("sessionId", sessionId)
            put("mode", "queue")
            putJsonArray("content") {
                addJsonObject {
                    put("type", "image")
                    put("mediaType", mediaType)
                    put("data", base64)
                    name?.let { put("name", it) }
                }
                if (text.isNotBlank()) {
                    addJsonObject { put("type", "text"); put("text", text) }
                }
            }
        }
        rpc.invoke("session.prompt", payload)
    }

    /** Send multiple images (+ optional text) as one user prompt. */
    suspend fun sessionPromptImages(sessionId: String, images: List<ImageData>, text: String) {
        val payload = buildJsonObject {
            put("sessionId", sessionId)
            put("mode", "queue")
            putJsonArray("content") {
                for (img in images) {
                    addJsonObject {
                        put("type", "image")
                        put("mediaType", img.mediaType)
                        put("data", img.base64)
                        img.name?.let { put("name", it) }
                    }
                }
                if (text.isNotBlank()) {
                    addJsonObject { put("type", "text"); put("text", text) }
                }
            }
        }
        rpc.invoke("session.prompt", payload)
    }

    /**
     * Send text plus staged file uploads as one prompt. Each file is a
     * `{ type:"file", receiptId }` part (see `PromptContentPart`), the only form
     * the host binds to a prompt; `receiptId` comes from `uploadAttachment`.
     */
    suspend fun sessionPromptFiles(sessionId: String, receiptIds: List<String>, text: String) {
        val payload = buildJsonObject {
            put("sessionId", sessionId)
            put("mode", "queue")
            putJsonArray("content") {
                for (rid in receiptIds) {
                    addJsonObject { put("type", "file"); put("receiptId", rid) }
                }
                if (text.isNotBlank()) {
                    addJsonObject { put("type", "text"); put("text", text) }
                }
            }
        }
        rpc.invoke("session.prompt", payload)
    }

    /** Archive (remove) a session from the active list. */
    /**
     * 让模型把一句人话翻译成手机动作(意图分析)。
     *
     * 走宿主的普通会话通道:在一个**专用的规划会话**里发一条带严格 JSON 输出
     * 要求的提示词,等这一轮结束再把最后一条 assistant 文本取回来。用一个长期
     * 存在的会话而不是每次新建,是为了省掉建会话的开销,也方便后面看历史。
     *
     * 失败(模型/网络/解析)一律返回 null,由调用方回退到本地规则解析——手机上
     * 不能因为桌面模型不可用就整页不能用。
     */
    suspend fun planPhoneIntent(
        plannerSessionId: String,
        text: String,
        timeoutMs: Long = 25_000,
    ): String? {
        // Baseline BEFORE prompting: the planner session is reused, so anything
        // already at or below this seq is a previous instruction's answer. Without
        // this the very first poll would return the stale reply from last time.
        val baseline = try {
            sessionHistory(plannerSessionId, maxMessages = 6).events.maxOfOrNull { it.event.seq } ?: 0L
        } catch (_: Exception) {
            0L
        }
        sessionPrompt(plannerSessionId, text)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            // delay() is the cancellation point: a cancelled Stop must leave here
            // instead of being captured by the catch below and spinning on.
            kotlinx.coroutines.delay(350)
            val history = try {
                sessionHistory(plannerSessionId, maxMessages = 30)
            } catch (t: kotlin.coroutines.cancellation.CancellationException) {
                throw t
            } catch (_: Exception) {
                continue
            }
            // Newest assistant reply that arrived strictly after our prompt.
            val reply = history.events
                .asReversed()
                .firstOrNull { it.event.type == "assistant/message" && it.event.seq > baseline }
                ?: continue
            val content = (reply.event.data["message"] as? JsonObject)?.get("content")
            val body = textOf(content)
            if (body.isNotBlank()) return body
        }
        return null
    }

    suspend fun archiveSession(sessionId: String) {
        val payload = buildJsonObject { put("sessionId", sessionId) }
        rpc.invoke("workspace.archiveSession", payload)
    }

    suspend fun subagentList(parentSessionId: String): SubagentListValue {
        val payload = buildJsonObject { put("parentSessionId", parentSessionId) }
        return json.decodeFromJsonElement(SubagentListValue.serializer(), rpc.invoke("subagent.list", payload))
    }

    suspend fun subagentHistory(parentSessionId: String, childSessionId: String, mode: String): SessionHistoryValue {
        val payload = buildJsonObject {
            put("parentSessionId", parentSessionId)
            put("childSessionId", childSessionId)
            put("mode", mode)
            put("maxMessages", 30)
        }
        return json.decodeFromJsonElement(SessionHistoryValue.serializer(), rpc.invoke("subagent.history", payload))
    }

    suspend fun subagentInterrupt(parentSessionId: String, childSessionId: String) {
        val payload = buildJsonObject {
            put("parentSessionId", parentSessionId)
            put("childSessionId", childSessionId)
            put("mode", "continuable")
        }
        rpc.invoke("subagent.interrupt", payload)
    }

    /**
     * Execute a slash command through the host command channel (`commands.execute`),
     * NOT as a chat message. Returns `null` when the line is not a known command
     * (the host logs nothing in that case), so callers can fall back to a plain prompt.
     */
    suspend fun commandExecute(sessionId: String, line: String): CommandExecuteValue? {
        // The host gateway expects a FLAT payload (agentId / line / submittedAttachments),
        // not an {"args":…} wrapper — a wrapper yields gateway/arguments-invalid.
        val payload = buildJsonObject {
            put("agentId", sessionId)
            put("line", line)
            putJsonArray("submittedAttachments") { /* no attachments for slash commands */ }
        }
        val v = rpc.invoke("commands/execute", payload)
        if (v is JsonNull) return null
        return json.decodeFromJsonElement(CommandExecuteValue.serializer(), v)
    }

    /** List the host's slash-command directory for one session (`commands.list`). */
    suspend fun commandList(sessionId: String): List<CommandDescriptor> {
        val v = rpc.invoke("commands/list", buildJsonObject { put("agentId", sessionId) })
        return json.decodeFromJsonElement(ListSerializer(CommandDescriptor.serializer()), v)
    }

    suspend fun agentPresetList(): AgentPresetListValue {
        val v = rpc.invoke("agentPreset.list", JsonObject(emptyMap()))
        return json.decodeFromJsonElement(AgentPresetListValue.serializer(), v)
    }

    suspend fun agentPresetSelect(sessionId: String, agentPreset: String) {
        val payload = buildJsonObject {
            put("sessionId", sessionId)
            put("agentPreset", agentPreset)
        }
        rpc.invoke("agentPreset.select", payload)
    }

    suspend fun sessionSearch(query: String): SessionSearchValue {
        val payload = buildJsonObject { put("query", query) }
        return json.decodeFromJsonElement(SessionSearchValue.serializer(), rpc.invoke("session.search", payload))
    }

    suspend fun workspaceCreate(path: String) {
        rpc.invoke("workspace.create", buildJsonObject { put("path", path) })
    }

    suspend fun workspaceRename(workspaceId: String, title: String) {
        rpc.invoke("workspace.rename", buildJsonObject { put("workspaceId", workspaceId); put("title", title) })
    }

    suspend fun workspaceDelete(workspaceId: String) {
        rpc.invoke("workspace.delete", buildJsonObject { put("workspaceId", workspaceId) })
    }

    /** Attach an existing session to a workspace so it shows under that workspace. */
    suspend fun workspaceInsertSessionBefore(workspaceId: String, sessionId: String) {
        val payload = buildJsonObject {
            put("workspaceId", workspaceId)
            put("sessionId", sessionId)
        }
        rpc.invoke("workspace.insertSessionBefore", payload)
    }

    /**
     * Model catalog for one session, parsed by hand.
     *
     * Field-by-field parsing means one odd provider entry (missing `name`, a
     * new nested object) can never fail the whole catalog and leave the model
     * menu empty.
     */
    suspend fun sessionModels(sessionId: String?): SessionModelsValue {
        val payload = buildJsonObject { if (sessionId != null) put("sessionId", sessionId) }
        val v = rpc.invoke("session.models", payload).jsonObject
        val groups = (v["groups"]?.jsonArray ?: JsonArray(emptyList())).mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val name = o["name"]?.jsonPrimitive?.contentOrNull ?: id
            val models = (o["models"]?.jsonArray ?: JsonArray(emptyList())).mapNotNull { me ->
                val mo = me as? JsonObject ?: return@mapNotNull null
                val mid = mo["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val mname = mo["name"]?.jsonPrimitive?.contentOrNull ?: mid
                val reasoning = (mo["reasoning"] as? JsonObject)?.let { ri ->
                    ReasoningInfo(
                        efforts = (ri["efforts"]?.jsonArray ?: JsonArray(emptyList())).mapNotNull { ee ->
                            val eo = ee as? JsonObject ?: return@mapNotNull null
                            val eid = eo["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                            ReasoningEffort(eid, eo["name"]?.jsonPrimitive?.contentOrNull ?: eid)
                        },
                        defaultEffort = ri["defaultEffort"]?.jsonPrimitive?.contentOrNull,
                    )
                }
                ModelInfo(mid, mname, reasoning)
            }
            ModelGroup(id, name, models)
        }
        val current = (v["current"] as? JsonObject)?.let { co ->
            val p = co["provider"]?.jsonPrimitive?.contentOrNull
            val m = co["model"]?.jsonPrimitive?.contentOrNull
            if (p != null && m != null) {
                ModelSelection(p, m, co["reasoningEffort"]?.jsonPrimitive?.contentOrNull)
            } else null
        }
        return SessionModelsValue(
            current = current,
            routable = v["routable"]?.jsonPrimitive?.booleanOrNull ?: false,
            groups = groups,
        )
    }

    suspend fun sessionSelectModel(
        sessionId: String,
        provider: String,
        model: String,
        reasoningEffort: String? = null,
    ) {
        val payload = buildJsonObject {
            put("sessionId", sessionId)
            put("provider", provider)
            put("model", model)
            if (reasoningEffort != null) put("reasoningEffort", reasoningEffort)
        }
        rpc.invoke("session.selectModel", payload)
    }

    suspend fun sessionCancel(sessionId: String) {
        rpc.invoke("session.cancel", buildJsonObject { put("sessionId", sessionId) })
    }

    suspend fun sessionRename(sessionId: String, title: String) {
        rpc.invoke("session.rename", buildJsonObject { put("sessionId", sessionId); put("title", title) })
    }

    suspend fun goalCreate(sessionId: String, objective: String) {
        rpc.invoke("goal.create", buildJsonObject { put("sessionId", sessionId); put("objective", objective) })
    }

    suspend fun goalPause(sessionId: String, refId: String, revision: Int) {
        rpc.invoke("goal.pause", goalPayload(sessionId, refId, revision))
    }

    suspend fun goalResume(sessionId: String, refId: String, revision: Int) {
        rpc.invoke("goal.resume", goalPayload(sessionId, refId, revision))
    }

    suspend fun goalComplete(sessionId: String, refId: String, revision: Int) {
        rpc.invoke("goal.complete", goalPayload(sessionId, refId, revision))
    }

    suspend fun goalClear(sessionId: String, refId: String, revision: Int) {
        rpc.invoke("goal.clear", goalPayload(sessionId, refId, revision))
    }

    suspend fun respondApproval(rpcId: String, sessionId: String, approvalId: String, allow: Boolean) {
        val value = buildJsonObject {
            put("approvalId", approvalId)
            put("sessionId", sessionId)
            put("outcome", if (allow) "allowed-once" else "rejected")
        }
        rpc.respond(rpcId, value)
    }

    suspend fun respondQuestion(
        rpcId: String,
        sessionId: String,
        answers: List<QuestionAnswer>,
    ) {
        val value = buildJsonObject {
            put("sessionId", sessionId)
            putJsonObject("answer") {
                putJsonArray("answers") {
                    for (a in answers) {
                        addJsonObject {
                            put("id", a.id)
                            putJsonArray("selected") { for (s in a.selected) add(s) }
                            a.custom?.let { put("custom", it) }
                        }
                    }
                }
            }
        }
        rpc.respond(rpcId, value)
    }

    /** List one directory (files + folders) via the remote-control browse route. */
    suspend fun listDirectory(path: String?): DirectoryListingValue {
        val q = if (path.isNullOrBlank()) "" else "?path=" + URLEncoder.encode(path, "UTF-8")
        val text = rpc.getText("/remote/list$q")
        return json.decodeFromString(DirectoryListingValue.serializer(), text)
    }

    /** Create a folder inside a directory via the remote-control route. */
    suspend fun mkdirFile(dir: String, name: String) {
        val payload = buildJsonObject { put("dir", dir); put("name", name) }
        rpc.postJson("/remote/mkdir", payload.toString())
    }

    /** Download a file's bytes via the remote-control file route. */
    suspend fun downloadFile(path: String): ByteArray {
        val q = "?path=" + URLEncoder.encode(path, "UTF-8")
        return rpc.getBytes("/remote/file$q")
    }

    /** Stream a file download to a local destination file. */
    suspend fun downloadFileTo(path: String, out: java.io.File): java.io.File {
        val q = "?path=" + URLEncoder.encode(path, "UTF-8")
        return rpc.downloadToFile("/remote/file$q", out)
    }

    /** Upload a file (base64) into a workspace directory via the remote-control route. */
    suspend fun uploadFile(dir: String, name: String, base64: String) {
        val payload = buildJsonObject {
            put("dir", dir)
            put("name", name)
            put("data", base64)
        }
        rpc.postJson("/remote/upload", payload.toString())
    }

    /**
     * 上传一个附件给会话(文档 / 表格 / 演示 / 图片 / 压缩包……)。
     *
     * 走宿主的 Typert Remote `fileUploads.upload`(JSON RPC,base64 data + name)。
     * 实测这个 RPC 在 legacy bridge(:3084,app 实际连接的端口)上可用,而流式
     * 原始字节路由 `/api/session/uploadFileBinary` 只在鉴权的 dsh web 侧注册,
     * 打到 bridge 会 404/400 "bad json"。返回收据 `receiptId`,发消息时用
     * `{ type:"file", receiptId }` 内容块绑定到提示词(见 `PromptContentPart`)。
     */
    suspend fun uploadAttachment(
        sessionId: String,
        name: String,
        bytes: ByteArray,
        mime: String?,
        onProgress: ((Float) -> Unit)? = null,
    ): UploadedAttachment {
        val payload = buildJsonObject {
            put("agentId", sessionId)
            putJsonObject("request") {
                put("data", java.util.Base64.getEncoder().encodeToString(bytes))
                put("name", name)
            }
        }
        val v = rpc.invoke("fileUploads.upload", payload)
        val value = v as? JsonObject ?: throw IOException("fileUploads.upload 响应不是对象")
        val file = value["file"]?.jsonObject ?: throw IOException("fileUploads.upload 响应缺少 value.file")
        return UploadedAttachment(
            name = (file["name"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: name,
            receiptId = (value["receiptId"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: "",
            bytes = (file["bytes"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: bytes.size.toLong(),
            attachmentId = (file["attachmentId"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: "",
        )
    }
    /** Delete a file or directory in the workspace via the remote-control route. */
    suspend fun deleteFile(path: String) {
        val payload = buildJsonObject { put("path", path) }
        rpc.postJson("/remote/delete", payload.toString())
    }

    /** Rename a file/directory (new name, same directory). */
    suspend fun renameFile(path: String, name: String) {
        val payload = buildJsonObject { put("path", path); put("name", name) }
        rpc.postJson("/remote/rename", payload.toString())
    }

    /** Duplicate a file in place ("-copy" suffix). */
    suspend fun copyFile(path: String) {
        val payload = buildJsonObject { put("path", path) }
        rpc.postJson("/remote/copy", payload.toString())
    }

    /** Describe the whole writable Web settings surface (raw JSON, most robust). */
    suspend fun settingsDescribe(): SettingsDescribeValue {
        val v = rpc.invoke("settings.describe", JsonObject(emptyMap()))
        return json.decodeFromJsonElement(SettingsDescribeValue.serializer(), v)
    }

    /** Raw `settings.describe` payload; parsed from the raw body so no schema layer can fail us. */
    suspend fun settingsDescribeRaw(): JsonObject {
        val text = rpc.invokeText("settings.describe", JsonObject(emptyMap()))
        val root = json.parseToJsonElement(text).jsonObject
        val result = root["result"]?.jsonObject
            ?: throw IllegalStateException("settings.describe: 响应缺少 result 字段")
        if (result["ok"]?.jsonPrimitive?.booleanOrNull != true) {
            throw IllegalStateException("settings.describe 被拒绝: ${result["error"]}")
        }
        return (result["value"] as? JsonObject)
            ?: throw IllegalStateException("settings.describe: value 不是对象")
    }

    /** Patch one settings namespace; `patch` maps setting refs to new values. */
    suspend fun settingsUpdate(ns: String, patch: JsonObject, expectedRevision: Long) {
        val payload = buildJsonObject {
            put("ns", ns)
            putJsonObject("patch") { patch.forEach { (k, v) -> put(k, v) } }
            put("expectedRevision", expectedRevision)
        }
        rpc.invoke("settings.update", payload)
    }
}

data class QuestionAnswer(
    val id: String,
    val selected: List<String> = emptyList(),
    val custom: String? = null,
)

/** One image payload for a multi-image prompt (base64). */
data class ImageData(
    val mediaType: String,
    val base64: String,
    val name: String? = null,
)

/** 宿主落盘后的附件信息。 */
data class UploadedAttachment(
    val name: String,
    /** 发消息用的 `type:"file"` 收据 id(prompt 绑定到暂存文件必需)。 */
    val receiptId: String,
    val bytes: Long,
    /** 宿主的持久附件 id(拉取图床缩略图用)。 */
    val attachmentId: String = "",
    /** 宿主识别的类型标签(如 "PDF 文档"),可能为空。 */
    val label: String? = null,
    val sniffedType: String? = null,
    /** 纯文本模型下宿主生成的图片描述。 */
    val imageDescription: String? = null,
    val deduplicated: Boolean = false,
)

/** 按扩展名猜 MIME;猜不到返回 null(交给宿主嗅探)。 */
internal fun mimeOfAttachment(name: String): String? =
    when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "txt", "md", "log" -> "text/plain"
        "csv" -> "text/csv"
        "json" -> "application/json"
        "html", "htm" -> "text/html"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "zip" -> "application/zip"
        "epub" -> "application/epub+zip"
        "rtf" -> "application/rtf"
        else -> null
    }
/** A durable image attachment fetched from the host (bytes as base64). */
data class AttachmentImage(
    val attachmentId: String,
    val mediaType: String = "image/png",
    val width: Int = 0,
    val height: Int = 0,
    val base64: String,
)

private fun goalPayload(sessionId: String, refId: String, revision: Int): JsonObject =
    buildJsonObject {
        put("sessionId", sessionId)
        putJsonObject("ref") {
            put("id", refId)
            put("revision", revision)
        }
    }
