package dev.dsh.remote.ui
import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.dsh.remote.data.ChatItem
import dev.dsh.remote.data.DshApi
import dev.dsh.remote.data.ImageData
import dev.dsh.remote.data.QuestionItem
import dev.dsh.remote.data.SessionEvent
import dev.dsh.remote.data.SessionHistoryValue
import dev.dsh.remote.data.SessionModelsValue
import dev.dsh.remote.data.GoalProjection
import dev.dsh.remote.data.JobView
import dev.dsh.remote.data.Permissions
import dev.dsh.remote.data.Plan
import dev.dsh.remote.data.QueueItem
import dev.dsh.remote.data.SessionStats
import dev.dsh.remote.data.TodoItem
import dev.dsh.remote.data.TokenUsageView
import dev.dsh.remote.data.TurnUsage
import dev.dsh.remote.data.ModelPrice
import dev.dsh.remote.data.Prices
import dev.dsh.remote.data.estimateCostCny
import dev.dsh.remote.data.fmtTokens
import dev.dsh.remote.data.ContextPressureView
import dev.dsh.remote.data.ContextBreakdownView
import dev.dsh.remote.data.SessionSummary
import dev.dsh.remote.data.SettingsDescribeValue
import dev.dsh.remote.data.SettingsNamespace
import dev.dsh.remote.data.SettingsStore
import dev.dsh.remote.data.WorkspaceView
import dev.dsh.remote.data.foldChat
import dev.dsh.remote.data.reasoningOf
import dev.dsh.remote.data.reasoningSummaryOf
import dev.dsh.remote.data.textOf
import dev.dsh.remote.data.imagesOf
import dev.dsh.remote.data.ImageRef
import dev.dsh.remote.data.TimelineSpan
import dev.dsh.remote.data.timelineSpansOf
import dev.dsh.remote.data.DirEntry
import dev.dsh.remote.data.DirectoryListingValue
import dev.dsh.remote.data.DeepseekBalanceResponse
import dev.dsh.remote.data.SubagentEntry
import dev.dsh.remote.data.SubagentListValue
import dev.dsh.remote.net.RpcClient
import dev.dsh.remote.net.TrustAll
import dev.dsh.remote.net.WsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
data class PendingQuestion(
    val rpcId: String,
    val sessionId: String,
    val questions: List<QuestionItem>,
)
data class PendingApproval(
    val rpcId: String,
    val sessionId: String,
    val approvalId: String,
    val toolName: String,
    val reason: String?,
)
class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = SettingsStore(application)
    private val json = dev.dsh.remote.net.DshJson.json
    val serverUrl: StateFlow<String> = settings.serverUrl
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsStore.DEFAULT_URL)
    /** Whether to post a notification (with sound) when a task completes. */
    val notifyDone: StateFlow<Boolean> = settings.notifyDone
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    /** Whether to post notifications for questions / approval requests. */
    val notifyPrompt: StateFlow<Boolean> = settings.notifyPrompt
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    fun setNotifyDone(v: Boolean) { viewModelScope.launch { settings.setNotifyDone(v) } }
    fun setNotifyPrompt(v: Boolean) { viewModelScope.launch { settings.setNotifyPrompt(v) } }
    /** UI appearance preference: "system" | "light" | "dark". */
    val themePreference: StateFlow<String> = settings.themePreference
        .stateIn(viewModelScope, SharingStarted.Eagerly, "system")
    fun setThemePreference(v: String) {
        viewModelScope.launch {
            settings.setThemePreference(v)
            // Keep the host's Web UI in the same light/dark mode as the app.
            syncWebTheme()
        }
    }
    /**
     * Mirror the app's light/dark preference onto the host's `ui-theme` so the
     * embedded Web settings page matches the app instead of always using whatever
     * the desktop last chose.
     */
    suspend fun syncWebTheme() {
        val dsh = api ?: return
        val pref = try { settings.themePreference.first() } catch (_: Exception) { return }
        // "system" is an app-side concept; the host only knows light/dark, and
        // sending it verbatim left the Web UI (settings panel + sidebar) on its
        // previous, usually dark, theme.
        val resolved = when (pref) {
            "light" -> "light"
            "dark" -> "dark"
            else -> {
                val night = try {
                    val ctx = getApplication<Application>()
                    (ctx.resources.configuration.uiMode and
                        android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                        android.content.res.Configuration.UI_MODE_NIGHT_YES
                } catch (_: Throwable) {
                    false
                }
                if (night) "dark" else "light"
            }
        }
        try {
            val raw = dsh.settingsDescribeRaw()
            val ns = raw["namespaces"]?.jsonArray
                ?.mapNotNull { it as? JsonObject }
                ?.firstOrNull { it["ns"]?.jsonPrimitive?.contentOrNull == "ui-theme" }
                ?: return
            val rev = ns["revision"]?.jsonPrimitive?.longOrNull ?: 0L
            dsh.settingsUpdate("ui-theme", buildJsonObject { put("preference", resolved) }, rev)
        } catch (_: Exception) {
        }
    }

    /** UI language preference: "zh" | "en". */
    /** Detailed conversation rendering (see SettingsStore.verbose). */
    val verbose: StateFlow<Boolean> = settings.verbose.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setVerbose(v: Boolean) { viewModelScope.launch { settings.setVerbose(v) } }

    val language: StateFlow<String> = settings.language
        .stateIn(viewModelScope, SharingStarted.Eagerly, "zh")
    fun setLanguage(v: String) {
        Strings.setLang(v)
        viewModelScope.launch { settings.setLanguage(v) }
    }
    /** DeepSeek platform API key (for balance/usage queries). */
    val deepseekApiKey: StateFlow<String> = settings.deepseekApiKey
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
    fun setDeepseekApiKey(key: String) { viewModelScope.launch { settings.setDeepseekApiKey(key) } }
    private val _deepseekBalance = MutableStateFlow<DeepseekBalanceResponse?>(null)
    val deepseekBalance: StateFlow<DeepseekBalanceResponse?> = _deepseekBalance.asStateFlow()
    private val _deepseekBalanceLoading = MutableStateFlow(false)
    val deepseekBalanceLoading: StateFlow<Boolean> = _deepseekBalanceLoading.asStateFlow()
    private val _deepseekBalanceError = MutableStateFlow<String?>(null)
    val deepseekBalanceError: StateFlow<String?> = _deepseekBalanceError.asStateFlow()
    fun queryDeepseekBalance() {
        viewModelScope.launch {
            val key = settings.deepseekApiKey.first()
            if (key.isBlank()) {
                _deepseekBalanceError.value = Strings.str("fill_api_key")
                return@launch
            }
            _deepseekBalanceLoading.value = true
            _deepseekBalanceError.value = null
            try {
                val result = withContext(Dispatchers.Default) {
                    val client = TrustAll.client().newBuilder()
                        .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    val req = okhttp3.Request.Builder()
                        .url("https://api.deepseek.com/user/balance")
                        .header("Authorization", "Bearer $key")
                        .get()
                        .build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                        val body = resp.body?.string() ?: throw Exception("empty response")
                        json.decodeFromString<DeepseekBalanceResponse>(body)
                    }
                }
                _deepseekBalance.value = result
            } catch (e: Exception) {
                _deepseekBalanceError.value = e.message ?: Strings.str("query_failed")
            } finally {
                _deepseekBalanceLoading.value = false
            }
        }
    }
    /** Fetch the current DeepSeek platform balance total (¥), or null when unavailable. */
    private suspend fun fetchBalanceTotal(): Double? {
        val key = settings.deepseekApiKey.first()
        if (key.isBlank()) return null
        return try {
            withContext(Dispatchers.Default) {
                val client = TrustAll.client().newBuilder()
                    .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val req = okhttp3.Request.Builder()
                    .url("https://api.deepseek.com/user/balance")
                    .header("Authorization", "Bearer $key")
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                    val body = resp.body?.string() ?: throw Exception("empty response")
                    json.decodeFromString<DeepseekBalanceResponse>(body)
                }
            }.balance_infos.sumOf { it.total_balance.toDoubleOrNull() ?: 0.0 }
        } catch (_: Exception) {
            null
        }
    }
    /** Show the real cost of a finished turn: remaining balance + balance difference. */
    private fun queryTurnCost(tu: TurnUsage?) {
        viewModelScope.launch {
            val total = fetchBalanceTotal()
            if (total != null) {
                val prev = lastBalance
                val text = if (prev != null) {
                    val cost = (prev - total).coerceAtLeast(0.0)
                    Strings.str("balance_and_cost", fmtYuan(total), fmtYuan(cost))
                } else {
                    Strings.str("balance_only", fmtYuan(total))
                }
                _chatItems.value = _chatItems.value + ChatItem.Cost(text)
                lastBalance = total
            } else if (tu != null && (tu.totalInput + tu.outputTokens) > 0) {
                // No API key / balance unavailable → fall back to a token estimate.
                val model = _models.value?.current?.model
                val cost = estimateCostCny(tu, model)
                val costText = Strings.str(
                    "turn_cost",
                    String.format(java.util.Locale.US, "%.3f", cost),
                    // Show everything the model read (prompt + cache hits), not just
                    // the uncached share: 0.2.x reports those separately and the raw
                    // value looked like the turn used almost no tokens.
                    fmtTokens(tu.totalInput),
                    fmtTokens(tu.outputTokens),
                )
                _chatItems.value = _chatItems.value + ChatItem.Cost(costText)
            }
        }
    }
    private fun fmtYuan(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()
    /** Base URL the client actually connected to (probed), not just the saved one. */
    private val _activeBaseUrl = MutableStateFlow<String?>(null)
    val activeBaseUrl: StateFlow<String?> = _activeBaseUrl.asStateFlow()
    private val _connecting = MutableStateFlow(false)
    val connecting: StateFlow<Boolean> = _connecting.asStateFlow()
    private val _fullTimelineSpans = MutableStateFlow<List<TimelineSpan>>(emptyList())
    val fullTimelineSpans: StateFlow<List<TimelineSpan>> = _fullTimelineSpans.asStateFlow()
    // Archived session ids (removed conversations, filtered from the list).
    private val _archivedSessionIds = MutableStateFlow<Set<String>>(emptySet())
    val archivedSessionIds: StateFlow<Set<String>> = _archivedSessionIds.asStateFlow()
    /**
     * Sessions archived from this device. Kept separately so a fresh host
     * workspace snapshot (which lags behind the archive call) cannot make a
     * just-deleted conversation reappear.
     */
    private val locallyArchived = MutableStateFlow<Set<String>>(emptySet())
    /**
     * sessionId → workspaceId attested by this device. The host's workspace
     * snapshot trails the attach call, so without this the freshly created
     * session drops back into the "ungrouped" bucket on the next refresh.
     */
    private val locallyAttached = mutableMapOf<String, String>()
    /** Fold locally-attested attachments into the current workspace list. */
    private fun applyLocalAttachments() {
        if (locallyAttached.isEmpty()) return
        _workspaces.value = _workspaces.value.map { w ->
            val extra = locallyAttached.entries
                .filter { it.value == w.workspaceId }
                .map { it.key }
                .filter { it !in w.sessionIds }
            if (extra.isEmpty()) w else w.copy(sessionIds = w.sessionIds + extra)
        }
    }
    // File browser state.
    private val _dirPath = MutableStateFlow<String?>(null)
    val dirPath: StateFlow<String?> = _dirPath.asStateFlow()
    private val _dirParent = MutableStateFlow<String?>(null)
    val dirParent: StateFlow<String?> = _dirParent.asStateFlow()
    private val _dirEntries = MutableStateFlow<List<DirEntry>>(emptyList())
    val dirEntries: StateFlow<List<DirEntry>> = _dirEntries.asStateFlow()
    private val _dirLoading = MutableStateFlow(false)
    val dirLoading: StateFlow<Boolean> = _dirLoading.asStateFlow()
    private val _dirError = MutableStateFlow<String?>(null)
    val dirError: StateFlow<String?> = _dirError.asStateFlow()
    // Subagent panel state.
    private val _subagents = MutableStateFlow<List<SubagentEntry>>(emptyList())
    val subagents: StateFlow<List<SubagentEntry>> = _subagents.asStateFlow()
    private val _subagentChat = MutableStateFlow<List<ChatItem>>(emptyList())
    val subagentChat: StateFlow<List<ChatItem>> = _subagentChat.asStateFlow()
    private val _subagentId = MutableStateFlow<String?>(null)
    val subagentId: StateFlow<String?> = _subagentId.asStateFlow()
    // Per-parent subagent catalog (subagent.list) for the sidebar tree — carries
    // mode/activity so continuable subagents stay resumable while one-shot ones drop.
    private val _subagentChildrenByParent = MutableStateFlow<Map<String, List<SubagentEntry>>>(emptyMap())
    val subagentChildrenByParent: StateFlow<Map<String, List<SubagentEntry>>> = _subagentChildrenByParent.asStateFlow()
    // Agent presets.
    private val _agentPresets = MutableStateFlow<List<dev.dsh.remote.data.AgentPresetEntry>>(emptyList())
    val agentPresets: StateFlow<List<dev.dsh.remote.data.AgentPresetEntry>> = _agentPresets.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _workspaces = MutableStateFlow<List<WorkspaceView>>(emptyList())
    val workspaces: StateFlow<List<WorkspaceView>> = _workspaces.asStateFlow()
    private val _sessions = MutableStateFlow<List<SessionSummary>>(emptyList())
    val sessions: StateFlow<List<SessionSummary>> = _sessions.asStateFlow()
    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()
    private val _chatItems = MutableStateFlow<List<ChatItem>>(emptyList())
    val chatItems: StateFlow<List<ChatItem>> = _chatItems.asStateFlow()
    /** Decoded chat-image thumbnails keyed by attachmentId. */
    private val _imageCache = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    val imageCache: StateFlow<Map<String, Bitmap>> = _imageCache.asStateFlow()
    private val _streaming = MutableStateFlow("")
    val streaming: StateFlow<String> = _streaming.asStateFlow()
    private val _streamingReasoning = MutableStateFlow("")
    val streamingReasoning: StateFlow<String> = _streamingReasoning.asStateFlow()
    private val _events = MutableStateFlow<List<SessionEvent>>(emptyList())
    val events: StateFlow<List<SessionEvent>> = _events.asStateFlow()
    val currentStats: StateFlow<SessionStats?> =
        combine(_currentSessionId, _sessions) { id, sessions ->
            sessions.firstOrNull { it.sessionId == id }?.projections?.values?.sessionStats
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val currentTokenUsage: StateFlow<TokenUsageView?> =
        combine(_currentSessionId, _sessions) { id, sessions ->
            decodeProjection(sessions.firstOrNull { it.sessionId == id }?.projections?.values?.tokenUsage, TokenUsageView.serializer())
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val currentContextPressure: StateFlow<ContextPressureView?> =
        combine(_currentSessionId, _sessions) { id, sessions ->
            decodeProjection(sessions.firstOrNull { it.sessionId == id }?.projections?.values?.contextPressure, ContextPressureView.serializer())
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val currentContextBreakdown: StateFlow<ContextBreakdownView?> =
        combine(_currentSessionId, _sessions) { id, sessions ->
            decodeProjection(sessions.firstOrNull { it.sessionId == id }?.projections?.values?.contextBreakdown, ContextBreakdownView.serializer())
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val currentTodos: StateFlow<List<TodoItem>> =
        combine(_currentSessionId, _sessions) { id, sessions ->
            decodeProjection(sessions.firstOrNull { it.sessionId == id }?.projections?.values?.todos, ListSerializer(TodoItem.serializer()))
                ?: emptyList()
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private fun <T> decodeProjection(el: JsonElement?, serializer: kotlinx.serialization.KSerializer<T>): T? =
        el?.let { runCatching { json.decodeFromJsonElement(serializer, it) }.getOrNull() }
    val currentPlan: StateFlow<Plan?> =
        combine(_currentSessionId, _sessions) { id, sessions ->
            sessions.firstOrNull { it.sessionId == id }?.projections?.values?.plan
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val currentPermissions: StateFlow<Permissions?> =
        combine(_currentSessionId, _sessions) { id, sessions ->
            sessions.firstOrNull { it.sessionId == id }?.projections?.values?.permissions
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val currentGoal: StateFlow<GoalProjection?> =
        combine(_currentSessionId, _sessions) { id, sessions ->
            sessions.firstOrNull { it.sessionId == id }?.projections?.values?.goal
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val _models = MutableStateFlow<SessionModelsValue?>(null)
    val models: StateFlow<SessionModelsValue?> = _models.asStateFlow()
    private val _pendingQuestions = MutableStateFlow<List<PendingQuestion>>(emptyList())
    val pendingQuestions: StateFlow<List<PendingQuestion>> = _pendingQuestions.asStateFlow()
    private val _pendingApprovals = MutableStateFlow<List<PendingApproval>>(emptyList())
    val pendingApprovals: StateFlow<List<PendingApproval>> = _pendingApprovals.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    /** Wall-clock start of the current run, driving the "running for mm:ss" indicator. */
    private val _runStartedAt = MutableStateFlow<Long?>(null)
    val runStartedAt: StateFlow<Long?> = _runStartedAt.asStateFlow()
    /**
     * When the in-flight turn actually began, per session.
     *
     * The bar used to be driven by "when did running flip to true", which on this
     * client is the moment it connected — so a session that was already running
     * counted from app launch instead of from the turn. A start is now recorded
     * only where it is really observed: a `turn/start` event, our own prompt, or —
     * for a turn that began before this client connected — the history timestamps.
     */
    private val runStartBySession = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** Mirror the known running state. Never invents a start time. */
    private fun setRunning(v: Boolean) {
        val sid = _currentSessionId.value
        if (!v && sid != null) runStartBySession.remove(sid)
        _runStartedAt.value = if (v && sid != null) runStartBySession[sid] else null
        _running.value = v
    }
    /** A turn just started here, so this client owns an accurate clock for it. */
    private fun startRunNow() {
        val sid = _currentSessionId.value
        if (sid != null) runStartBySession[sid] = System.currentTimeMillis()
        _runStartedAt.value = if (sid != null) runStartBySession[sid] else null
        _running.value = true
    }
    private val _jobs = MutableStateFlow<List<JobView>>(emptyList())
    val jobs: StateFlow<List<JobView>> = _jobs.asStateFlow()
    /** Session ids → finished-at timestamp whose turn finished while NOT watching them. */
    private val _finishedSessions = MutableStateFlow<Map<String, Long>>(emptyMap())
    val finishedSessions: StateFlow<Map<String, Long>> = _finishedSessions.asStateFlow()
    private val _queueItems = MutableStateFlow<List<QueueItem>>(emptyList())
    /** Pending messages sitting in the host's native inbox queue (placement "queued"). */
    val queueItems: StateFlow<List<QueueItem>> = _queueItems.asStateFlow()
    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()
    // Per-session drafts (WeChat-style), keyed by sessionId; persisted to DataStore.
    // ConcurrentHashMap: accessed from both the UI thread and WS frame handlers.
    private val draftBySession = java.util.concurrent.ConcurrentHashMap<String, String>()
    private var draftsLoaded = false
    private var draftPersistJob: Job? = null
    private val _loadingSession = MutableStateFlow(false)
    val loadingSession: StateFlow<Boolean> = _loadingSession.asStateFlow()
    private suspend fun ensureDraftsLoaded() {
        if (draftsLoaded) return
        draftsLoaded = true
        try {
            draftBySession.putAll(settings.loadDrafts())
        } catch (_: Exception) {
        }
    }
    fun setDraft(text: String) {
        _draft.value = text
        _currentSessionId.value?.let { sid ->
            draftBySession[sid] = text
            persistDrafts()
        }
    }
    /** Insert a slash command into the composer as text so the user can review it before sending. */
    fun insertCommand(command: String) {
        val current = _draft.value
        val sep = if (current.isEmpty() || current.last() == ' ' || current.last() == '\n') "" else " "
        setDraft(if (current.isEmpty()) "$command " else "$current$sep$command ")
    }
    private fun persistDrafts() {
        draftPersistJob?.cancel()
        val snapshot = draftBySession.toMap()
        draftPersistJob = viewModelScope.launch {
            delay(400)
            try {
                settings.saveDrafts(snapshot)
            } catch (_: Exception) {
            }
        }
    }
    private val _hasMore = MutableStateFlow(false)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()
    private val _loadingOlder = MutableStateFlow(false)
    val loadingOlder: StateFlow<Boolean> = _loadingOlder.asStateFlow()
    private var api: DshApi? = null
    private var baseUrl: String? = null
    private var wsClient: okhttp3.OkHttpClient? = null
    private var lastSeqBySession = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val runningBySession = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val queueBySession = java.util.concurrent.ConcurrentHashMap<String, List<QueueItem>>()
    private val jobsBySession = java.util.concurrent.ConcurrentHashMap<String, List<JobView>>()
    private var _oldestSeq: Long? = null
    private var fullTimelineSessionId: String? = null
    private var turnUsage: TurnUsage? = null

    /** Fire-and-forget download used by tool file cards. */
    fun scopeDownload(path: String) {
        viewModelScope.launch { downloadWorkspaceFile(path) }
    }

    /** Where downloaded workspace files land: /sdcard/DSHRemote. */
    private val downloadDirName = "DSHRemote"

    /** User-chosen folder under Downloads for saved files. */
    val downloadDir: StateFlow<String> =
        settings.downloadDir.stateIn(viewModelScope, SharingStarted.Eagerly, "DSHRemote")

    fun setDownloadDir(v: String) {
        viewModelScope.launch { settings.setDownloadDir(v) }
    }

    /** SAF folder chosen in Settings; empty = 下载/<downloadDir>/. */
    val downloadTree: StateFlow<String> =
        settings.downloadTree.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    fun setDownloadTree(uri: String) {
        viewModelScope.launch { settings.setDownloadTree(uri) }
    }

    /**
     * Dedicated HTTP lane for file downloads.
     *
     * Shares no connection pool with the RPC/event-stream client, so a long transfer
     * gets its own TCP connection (own congestion window) instead of competing with
     * the conversation traffic for one. Also relaxed timeouts for slow links.
     */
    private val downloadClient: okhttp3.OkHttpClient by lazy {
        dev.dsh.remote.net.TrustAll.client().newBuilder()
            .connectionPool(okhttp3.ConnectionPool(4, 5, java.util.concurrent.TimeUnit.MINUTES))
            .readTimeout(600, java.util.concurrent.TimeUnit.SECONDS)
            .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    /** Resolve a card's path against the session's cwd when it is not absolute. */
    private fun absoluteWorkspacePath(path: String): String {
        val p = path.trim()
        val absolute = p.startsWith("/") || (p.length > 2 && p[1] == ':')
        if (absolute) return p
        val cwd = _sessions.value
            .firstOrNull { it.sessionId == _currentSessionId.value }
            ?.cwd
            ?.trimEnd('/', '\\')
        if (cwd.isNullOrBlank()) return p
        val sep = if (cwd.contains('\\') || (cwd.length > 1 && cwd[1] == ':')) "\\" else "/"
        return cwd + sep + p.replace('/', sep[0]).replace('\\', sep[0])
    }

    /** Files already downloaded, read from wherever downloads currently go. */
    suspend fun listDownloads(): List<DownloadEntry> = withContext(Dispatchers.IO) {
        val ctx = getApplication<Application>()
        val out = ArrayList<DownloadEntry>()
        val tree = downloadTree.value
        try {
            if (tree.isNotBlank()) {
                val treeUri = android.net.Uri.parse(tree)
                val docId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
                val parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(
                    treeUri, docId,
                )
                ctx.contentResolver.query(
                    children,
                    arrayOf(
                        android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        android.provider.DocumentsContract.Document.COLUMN_SIZE,
                        android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
                    ),
                    null, null, null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        val childId = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        val size = if (c.isNull(2)) 0L else c.getLong(2)
                        val mime = c.getString(3) ?: "application/octet-stream"
                        if (mime == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) continue
                        val docUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                        out.add(DownloadEntry(docUri.toString(), name, size, mime))
                    }
                }
                // `parent` is only needed to keep the URI shape explicit.
                if (parent == android.net.Uri.EMPTY) out.clear()
            } else if (android.os.Build.VERSION.SDK_INT >= 29) {
                val collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
                val rel = android.os.Environment.DIRECTORY_DOWNLOADS + "/" + downloadDir.value
                ctx.contentResolver.query(
                    collection,
                    arrayOf(
                        android.provider.MediaStore.MediaColumns._ID,
                        android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
                        android.provider.MediaStore.MediaColumns.SIZE,
                        android.provider.MediaStore.MediaColumns.MIME_TYPE,
                    ),
                    android.provider.MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?",
                    arrayOf("$rel%"),
                    android.provider.MediaStore.MediaColumns.DISPLAY_NAME + " DESC",
                )?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        val name = c.getString(1) ?: continue
                        val size = if (c.isNull(2)) 0L else c.getLong(2)
                        val mime = c.getString(3) ?: "application/octet-stream"
                        val uri = android.content.ContentUris.withAppendedId(collection, id)
                        out.add(DownloadEntry(uri.toString(), name, size, mime))
                    }
                }
            }
        } catch (e: Throwable) {
            log("list downloads failed: ${e.message}")
        }
        out
    }

    /** Delete one downloaded file (MediaStore row or SAF document). */
    suspend fun deleteDownloaded(entry: DownloadEntry): Boolean = withContext(Dispatchers.IO) {
        val ctx = getApplication<Application>()
        try {
            val uri = android.net.Uri.parse(entry.uri)
            val ok = if (downloadTree.value.isNotBlank()) {
                android.provider.DocumentsContract.deleteDocument(ctx.contentResolver, uri)
            } else {
                ctx.contentResolver.delete(uri, null, null) > 0
            }
            if (ok) toast("已删除 ${entry.name}") else toast("删除失败")
            ok
        } catch (e: Throwable) {
            log("delete failed: ${e.message}")
            toast("删除失败：${e.message ?: "unknown"}")
            false
        }
    }

    /** Best-effort MIME from the file name; MediaStore often says octet-stream. */
    private fun mimeFor(name: String, fallback: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        val m = when (ext) {
            "wav" -> "audio/x-wav"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "ogg" -> "audio/ogg"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "md" -> "text/markdown"
            "json" -> "application/json"
            "csv" -> "text/csv"
            "html", "htm" -> "text/html"
            "zip" -> "application/zip"
            "apk" -> "application/vnd.android.package-archive"
            "kt", "java", "py", "js", "ts", "css", "xml", "yml", "yaml", "log" -> "text/plain"
            else -> return if (fallback.isBlank()) "*/*" else fallback
        }
        // A generic type from the store is worth less than the extension guess.
        return if (fallback.isBlank() || fallback == "application/octet-stream") m else fallback
    }

    /** Hand a downloaded file to whichever app can display it. */
    fun openDownloaded(e: DownloadEntry) {
        // Deliberately the plain pre-1.5.68 path: the chooser/MIME-guessing rewrite
        // broke opening on Android 16, so this is the version that worked.
        try {
            val ctx = getApplication<Application>()
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(android.net.Uri.parse(e.uri), e.mime)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
        } catch (err: Throwable) {
            toast("没有能打开该文件的应用（${err.javaClass.simpleName}）")
        }
    }

    /** Human-readable tail of the chosen SAF folder, for display. */    fun downloadTreeLabel(uri: String): String {
        if (uri.isBlank()) return "未选择（默认 下载/$downloadDirName）"
        return try {
            val docId = android.provider.DocumentsContract.getTreeDocumentId(android.net.Uri.parse(uri))
            docId.substringAfter(':').ifBlank { docId }
        } catch (_: Throwable) {
            uri
        }
    }

    /**
     * Copy one workspace file from the host into a visible folder on the phone.
     *
     * The bridge exposes `/remote/file?path=…` (workspace file bytes), which is the
     * only route that can serve arbitrary files. Files go to a dedicated folder at
     * the storage root so they are easy to find afterwards.
     *
     * @returns the saved display name, or null on failure.
     */
    /** Progress notification channel for downloads. */
    /** Silent channel for the running progress bar. */
    /** Set by a notification tap so the UI can jump straight to the downloads list. */
    private val _openDownloads = MutableStateFlow(false)
    val openDownloads: StateFlow<Boolean> = _openDownloads.asStateFlow()

    fun requestOpenDownloads() { _openDownloads.value = true }

    fun consumeOpenDownloads() { _openDownloads.value = false }

    private val PROGRESS_NOTIFY_ID = 4711
    private val DONE_NOTIFY_ID = 4712
    private fun downloadChannel(): String {
        val ctx = getApplication<Application>()
        val id = "dsh-downloads"
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            val mgr = ctx.getSystemService(android.content.Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
            if (mgr.getNotificationChannel(id) == null) {
                mgr.createNotificationChannel(
                    android.app.NotificationChannel(
                        id, "下载进度", android.app.NotificationManager.IMPORTANCE_LOW,
                    )
                )
            }
        }
        return id
    }

    private fun doneChannel(): String {
        val ctx = getApplication<Application>()
        val id = "dsh-download-done"
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            val mgr = ctx.getSystemService(android.content.Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
            if (mgr.getNotificationChannel(id) == null) {
                mgr.createNotificationChannel(
                    android.app.NotificationChannel(
                        id, "文件下载", android.app.NotificationManager.IMPORTANCE_LOW,
                    )
                )
            }
        }
        return id
    }

    /** Clear the progress row and post the "finished" notification (with sound). */
    private fun notifyDownloadFinished(name: String, failed: Boolean) {
        try {
            val ctx = getApplication<Application>()
            val mgr = ctx.getSystemService(android.content.Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
            mgr.cancel(PROGRESS_NOTIFY_ID)
            val open = android.app.PendingIntent.getActivity(
                ctx, 0,
                android.content.Intent(ctx, dev.dsh.remote.MainActivity::class.java).apply {
                    putExtra("dsh.openDownloads", true)
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
            val b = androidx.core.app.NotificationCompat.Builder(
                ctx, if (failed) downloadChannel() else doneChannel()
            )
                .setSmallIcon(dev.dsh.remote.R.drawable.dsh_ic_download_outline16)
                .setContentTitle(if (failed) "下载失败" else "下载完成")
                .setContentText(name)
                .setContentIntent(open)
                .setAutoCancel(true)
            if (!failed) {
                b.setDefaults(androidx.core.app.NotificationCompat.DEFAULT_SOUND or
                    androidx.core.app.NotificationCompat.DEFAULT_VIBRATE)
            }
            mgr.notify(DONE_NOTIFY_ID, b.build())
        } catch (_: Throwable) {
        }
    }

    /** One reused progress notification so the bar animates instead of stacking. */
    private fun notifyDownload(name: String, received: Long, total: Long, done: Boolean, failed: Boolean = false) {
        try {
            val ctx = getApplication<Application>()
            val b = androidx.core.app.NotificationCompat.Builder(ctx, if (done && !failed) doneChannel() else downloadChannel())
                .setSmallIcon(dev.dsh.remote.R.drawable.dsh_ic_download_outline16)
                .setContentTitle(if (failed) "下载失败" else if (done) "下载完成" else "正在下载")
                .setContentText(name)
                .setOnlyAlertOnce(true)
                .setOngoing(!done)
            if (!done && !failed) {
                if (total > 0) {
                    b.setProgress(100, ((received * 100) / total).toInt(), false)
                } else {
                    b.setProgress(0, 0, true)
                }
            } else if (done) {
                b.setProgress(0, 0, false)
            }
            val mgr = ctx.getSystemService(android.content.Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
            mgr.notify(PROGRESS_NOTIFY_ID, b.build())
        } catch (_: Throwable) {
        }
    }

    /**
     * Stream one workspace file into 下载/DSHRemote with user-visible feedback.
     *
     * Shows a toast when it starts and a progress notification while the bytes flow,
     * so a long copy over the tailnet never looks like nothing happened.
     */
    suspend fun downloadWorkspaceFile(path: String): String? = withContext(Dispatchers.IO) {
        val base = baseUrl ?: return@withContext null
        val client = downloadClient   // dedicated lane: separate TCP from RPC/events
        val ctx = getApplication<Application>()
        val name = path.substringAfterLast('/').substringAfterLast('\\').ifBlank { "file" }
        var total = -1L
        var received = 0L
        try {
            toast("正在下载 $name …")
            notifyDownload(name, 0, 0, done = false)
            // Card paths can be relative to the session cwd while the bridge only
            // serves absolute paths — that is why other sessions' files failed.
            val encoded = android.net.Uri.encode(absoluteWorkspacePath(path))
            val req = okhttp3.Request.Builder().url("$base/remote/file?path=$encoded").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    notifyDownloadFinished(name, failed = true)
                    val why = try { resp.body?.string()?.take(80) } catch (_: Throwable) { null }
                    toast("下载失败：HTTP ${resp.code}" + (why?.let { " · $it" } ?: ""))
                    return@withContext null
                }
                total = resp.body?.contentLength() ?: -1L
                val tree = downloadTree.value
                val sink = if (tree.isNotBlank()) {
                    // Save straight into the folder picked in the system file manager.
                    val treeUri = android.net.Uri.parse(tree)
                    val docId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
                    val parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    val child = android.provider.DocumentsContract.createDocument(
                        ctx.contentResolver, parent, "application/octet-stream", name,
                    ) ?: return@withContext null
                    ctx.contentResolver.openOutputStream(child) ?: return@withContext null
                } else if (android.os.Build.VERSION.SDK_INT >= 29) {
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(
                            android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                            android.os.Environment.DIRECTORY_DOWNLOADS + "/" + downloadDir.value,
                        )
                    }
                    val uri = ctx.contentResolver
                        .insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    if (uri == null) {
                        // MediaStore refuses some names/types; fall back to the app
                        // folder so the download still completes instead of dying.
                        val dir = java.io.File(ctx.getExternalFilesDir(null), downloadDir.value)
                        dir.mkdirs()
                        java.io.File(dir, name).outputStream()
                    } else {
                        ctx.contentResolver.openOutputStream(uri)
                            ?: java.io.File(ctx.getExternalFilesDir(null), name).outputStream()
                    }
                } else {
                    val dir = java.io.File(ctx.getExternalFilesDir(null)?.parentFile?.parentFile, "")
                    val out = java.io.File(dir, downloadDir.value)
                    out.mkdirs()
                    java.io.File(out, name).outputStream()
                }
                sink.use { out ->
                    val input = resp.body?.byteStream() ?: return@withContext null
                    input.use { ins ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val n = ins.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            received += n
                            notifyDownload(name, received, total, done = false)
                        }
                    }
                }
                notifyDownloadFinished(name, failed = false)
                toast("下载完成：下载/${downloadDir.value}/$name")
                name
            }
        } catch (e: Throwable) {
            log("download failed: ${e.message}")
            notifyDownloadFinished(name, failed = true)
            toast("下载失败：${e.message ?: "unknown"}")
            null
        }
    }
    private val _turnUsageFlow = MutableStateFlow<TurnUsage?>(null)
    val turnUsageFlow: StateFlow<TurnUsage?> = _turnUsageFlow.asStateFlow()

    /** Whether dsh-better-sidebar is installed on the host; null until checked. */
    private var betterSidebar: Boolean? = null

    /**
     * Ask the host whether the sidebar plugin is present.
     *
     * Only a *positive* answer is ever cached. A negative one used to be cached
     * forever, so a single failed probe — the bridge restarting, a reconnect race,
     * one dead request — left both the workbench and the 文件变动 card showing
     * "未安装插件" for the rest of the process, with no path back except killing
     * the app. Unknown or failed probes now return true so the panel loads and the
     * page itself decides (it reports missing via `DshPanel.missing`).
     */
    suspend fun hasBetterSidebar(): Boolean = withContext(Dispatchers.IO) {
        betterSidebar?.let { return@withContext it }
        val dsh = api ?: return@withContext true
        try {
            val raw = dsh.settingsDescribeRaw()
            val present = raw["namespaces"]?.jsonArray
                ?.mapNotNull { it as? JsonObject }
                ?.any { it["ns"]?.jsonPrimitive?.contentOrNull == "better-sidebar" } ?: false
            if (present) betterSidebar = true
            true // optimistic: let the page report a genuinely absent plugin
        } catch (_: Exception) {
            true // transient failure: never poison the cache, retry on the next open
        }
    }
    private var lastBalance: Double? = null
    private var muxJob: kotlinx.coroutines.Job? = null
    private var hostJob: kotlinx.coroutines.Job? = null
    /** Endpoint the current frame streams were opened against. */
    private var framesUrl: String? = null
    /** Last time any frame arrived; a long gap means a half-open socket. */
    @Volatile
    private var lastFrameAt: Long = 0L
    companion object {
        private const val MAX_EVENTS = 800
        /** Whole-host reachability probe budget, shared by all candidates. */
        private const val PROBE_TIMEOUT_MS = 8_000L
        /** Streams this idle on resume are treated as half-open and rebuilt. */
        private const val STALE_STREAM_MS = 60_000L
        /** Leading slash-command token, mirroring the host `parseCommand` grammar. */
        private val COMMAND_LINE_REGEX = Regex("^/([a-z][a-z0-9_-]*)(?=$|[\\s])")
        /** Remote `prices.json` used to refresh the cost table on app start. */
        private val PRICES_URL = "https://raw.githubusercontent.com/SCSpotato/dsh-remote/main/prices.json"
    }
    init {
        // Theme changes made inside the app must reach the host, otherwise the Web
        // settings panel and the sidebar keep their previous colours.
        viewModelScope.launch {
            settings.themePreference.collect {
                if (api != null) {
                    delay(200L)
                    try { syncWebTheme() } catch (_: Exception) {}
                }
            }
        }
        // Apply the saved language before any UI composes, then auto-connect.
        viewModelScope.launch { Strings.setLang(settings.language.first()) }
        // A WebView cache carried over from a previous build renders the settings and
        // sidebar panels blank while every request still answers 200. Wiping it once
        // per installed version costs nothing and removes that whole failure class.
        viewModelScope.launch(Dispatchers.Main) { clearWebViewCacheIfNewVersion() }
        fetchPrices()
        connect()
    }

    /**
     * Drop the WebView cache when the installed build changes.
     *
     * Both panels are WebViews over the host's own page, and the bundle they load is
     * cached for a year (`Cache-Control: immutable`). After an upgrade or a host
     * frontend change that cache can describe a bundle the page no longer matches,
     * and the result is a blank panel with a clean network log — indistinguishable
     * from "cannot connect". Clearing once per version keeps the two in step.
     */
    private fun clearWebViewCacheIfNewVersion() {
        try {
            val ctx = getApplication<Application>()
            val prefs = ctx.getSharedPreferences("dsh_web_cache", android.content.Context.MODE_PRIVATE)
            val now = try {
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionCode
            } catch (_: Throwable) {
                -1
            }
            if (prefs.getInt("version", -1) == now) return
            clearWebViewCache()
            prefs.edit().putInt("version", now).apply()
            log("webview cache cleared for version $now")
        } catch (e: Throwable) {
            log("webview cache reset failed: ${e.message}")
        }
    }

    /** Wipe the WebView cache and the panel bundle cache. Must run on the main thread. */
    fun clearWebViewCache() {
        try {
            android.webkit.WebStorage.getInstance().deleteAllData()
        } catch (_: Throwable) {
        }
        try {
            val ctx = getApplication<Application>()
            android.webkit.WebView(ctx).apply {
                clearCache(true)
                clearHistory()
                clearFormData()
                destroy()
            }
        } catch (_: Throwable) {
        }
        try {
            java.io.File(getApplication<Application>().filesDir, "webcache").deleteRecursively()
        } catch (_: Throwable) {
        }
    }
    /** Fetch the latest pricing table from `prices.json`; built-in defaults are kept on failure. */
    private fun fetchPrices() {
        viewModelScope.launch {
            try {
                val client = TrustAll.client()
                val req = okhttp3.Request.Builder().url(PRICES_URL).build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val text = resp.body?.string() ?: return@launch
                        parsePrices(json.parseToJsonElement(text).jsonObject)
                    }
                }
            } catch (_: Exception) {
                Prices.resetDefaults()
            }
        }
    }
    private fun parsePrices(obj: JsonObject) {
        try {
            obj["default"]?.jsonObject?.let { d ->
                Prices.default = ModelPrice(
                    inputMiss = d["inputMiss"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: Prices.default.inputMiss,
                    inputHit = d["inputHit"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: Prices.default.inputHit,
                    output = d["output"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: Prices.default.output,
                )
            }
            obj["models"]?.jsonObject?.let { ms ->
                for ((name, v) in ms) {
                    val mv = v.jsonObject
                    Prices.byModel[name] = ModelPrice(
                        inputMiss = mv["inputMiss"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 1.5,
                        inputHit = mv["inputHit"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.05,
                        output = mv["output"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 4.5,
                    )
                }
            }
        } catch (_: Exception) {
            Prices.resetDefaults()
        }
    }
    fun setServerUrl(url: String) {
        viewModelScope.launch { settings.setServerUrl(url) }
    }
    private fun startForegroundService() {
        val ctx = getApplication<Application>()
        val intent = android.content.Intent(ctx, dev.dsh.remote.service.DshForegroundService::class.java)
        androidx.core.content.ContextCompat.startForegroundService(ctx, intent)
    }
    /** Read the dsh Web settings surface, parsed by hand so a shape change can't break it. */
    suspend fun loadWebSettings(): SettingsDescribeValue {
        // The panel can be opened before the background auto-connect finishes;
        // wait briefly instead of failing with a bare "not loaded".
        var waited = 0L
        while (api == null && waited < 10_000L) {
            kotlinx.coroutines.delay(200L)
            waited += 200L
        }
        val dsh = api ?: throw IllegalStateException(
            "未连接：自动连接未完成或地址不可达。请在设置页点\"重新连接\"后再试。"
        )
        val raw = dsh.settingsDescribeRaw()
        val writable = raw["writable"]?.jsonPrimitive?.booleanOrNull ?: false
        val list = (raw["namespaces"]?.jsonArray ?: return SettingsDescribeValue(writable = writable))
            .mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val ns = o["ns"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                SettingsNamespace(
                    ns = ns,
                    value = o["value"] ?: JsonObject(emptyMap()),
                    revision = o["revision"]?.jsonPrimitive?.longOrNull ?: 0L,
                )
            }
        if (list.isEmpty()) {
            throw IllegalStateException("主机返回了空的设置列表（namespaces=0），可能版本不匹配。")
        }
        return SettingsDescribeValue(writable = writable, namespaces = list)
    }
    /** Load the model catalog if it has not arrived yet (menu opened too early). */
    fun ensureModels() {
        if (_models.value == null) refreshModels(force = true)
    }
    /** Whether the host serves the lightweight settings page.
     *
     * That page is ~12 KB and talks to the settings API directly, versus the full
     * Web app (~474 KB) that the phone would otherwise have to download first.
     */
    suspend fun hasMiniSettings(): Boolean = withContext(Dispatchers.IO) {
        val client = wsClient ?: return@withContext false
        val base = baseUrl ?: return@withContext false
        try {
            val req = okhttp3.Request.Builder().url("$base/mini/settings.html").head().build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }
    /** Local proxy that serves the Web bundle from phone storage. */
    private var webProxy: dev.dsh.remote.net.WebProxyServer? = null
    /** Loopback TCP tunnel that gives the Web panels a local-looking origin. */
    private var loopbackTunnel: dev.dsh.remote.net.LoopbackTunnel? = null

    /**
     * Whether the Web panels are loaded through the loopback tunnel.
     *
     * OFF: on-device the tunnel answered every request (the host access log shows
     * 55 requests with `host: 127.0.0.1:3080`, all 200) yet both panels came up
     * blank, while loading the host straight from its own URL renders them in a
     * couple of seconds. The direct arrangement is therefore the default again.
     *
     * The tunnel stays in the tree, unused, because its one benefit is real:
     * dsh's web client only offers the settings surfaces when
     * `location.hostname` is loopback, so the settings → 模型 page needs a
     * local-looking origin. Flip this flag once that can be debugged on-device.
     */
    private val WEB_PANELS_VIA_LOOPBACK = false

    /**
     * URL the embedded Web panels (设置 / 侧边栏) should load.
     *
     * Default: the host URL itself, which renders both panels here in seconds.
     * With [WEB_PANELS_VIA_LOOPBACK] the page is served from 127.0.0.1 through a
     * raw TCP tunnel, which would also unlock 设置 → 模型.
     */
    fun webEntryUrl(): String {
        val base = (activeBaseUrl.value ?: serverUrl.value).ifBlank { "http://127.0.0.1:8080" }.trimEnd('/')
        if (!WEB_PANELS_VIA_LOOPBACK) return "$base/"
        return try {
            val uri = java.net.URI(base)
            val host = uri.host ?: return "$base/"
            val port = if (uri.port > 0) uri.port else 80
            if (isLoopbackHost(host)) return "$base/"
            loopbackTunnel?.let { if (it.port > 0) return "http://127.0.0.1:${it.port}/" }
            val tunnel = dev.dsh.remote.net.LoopbackTunnel(host, port)
            val bound = tunnel.start()
            loopbackTunnel = tunnel
            log("web tunnel 127.0.0.1:$bound -> $host:$port")
            "http://127.0.0.1:$bound/"
        } catch (e: Throwable) {
            log("web tunnel failed: ${e.message}; loading the host directly")
            "$base/"
        }
    }

    private fun isLoopbackHost(host: String): Boolean =
        host == "localhost" || host == "::1" || host == "[::1]" ||
            (host.startsWith("127.") && host.split('.').let { p -> p.size == 4 && p.all { it.toIntOrNull() != null } })

    /** Start (once) the local Web proxy and return its port. */
    fun ensureWebProxy(): Int? {
        val base = baseUrl ?: return null
        webProxy?.let { if (it.wasStarted()) return it.listeningPort }
        return try {
            val app = getApplication<Application>()
            val dir = java.io.File(app.filesDir, "webcache")
            val client = wsClient ?: dev.dsh.remote.net.TrustAll.client()
            val srv = dev.dsh.remote.net.WebProxyServer(base, dir, client)
            srv.start(5_000, true)
            webProxy = srv
            log("web proxy started on ${srv.listeningPort}")
            srv.listeningPort
        } catch (e: Throwable) {
            log("web proxy failed: ${e.message}")
            null
        }
    }
    /** Download the whole Web bundle so later opens are instant. */
    suspend fun precacheWeb(): Int = withContext(Dispatchers.IO) {
        val srv = webProxy ?: return@withContext 0
        try { srv.precache() } catch (_: Exception) { 0 }
    }
    private fun log(msg: String) {
        try { android.util.Log.i("DshRemote", msg) } catch (_: Throwable) {}
    }
    /** Slash commands the host advertises; feeds the in-chat 动作 panel. */
    private val _commands = MutableStateFlow<List<dev.dsh.remote.data.CommandDescriptor>>(emptyList())
    val commands: StateFlow<List<dev.dsh.remote.data.CommandDescriptor>> = _commands.asStateFlow()
    fun loadCommands() {
        val sessionId = _currentSessionId.value ?: return
        viewModelScope.launch {
            try {
                _commands.value = api?.commandList(sessionId) ?: emptyList()
            } catch (_: Exception) {
            }
        }
    }
    /** Run one slash command immediately (the 动作 panel executes, not inserts). */
    fun executeCommand(line: String) {
        val sessionId = _currentSessionId.value ?: return
        viewModelScope.launch {
            try {
                runCommand(sessionId, line)
            } catch (e: Exception) {
                toast("命令失败: ${e.message ?: "unknown"}")
            }
        }
    }
    /** Provider/model catalog for settings pickers; works without an open session. */
    suspend fun loadModelCatalog(): List<dev.dsh.remote.data.ModelGroup> {
        val dsh = api ?: return emptyList()
        return try {
            dsh.sessionModels(_currentSessionId.value).groups
        } catch (_: Exception) {
            emptyList()
        }
    }
    /** Patch one Web settings namespace and let the caller refresh afterwards. */
    suspend fun updateWebSetting(ns: String, patch: JsonObject, revision: Long) {
        api?.settingsUpdate(ns, patch, revision)
    }
    fun connect(url: String? = null) {
        viewModelScope.launch {
            _error.value = null
            _connecting.value = true
            try {
                val preferred = (url ?: serverUrl.first()).trimEnd('/')
                val client = TrustAll.client()
                // A saved/pinned host can rot (stale MagicDNS suffix, changed
                // node IP). Probe the preferred base URL first, then every
                // built-in candidate, and keep whichever answers.
                val url2 = probeReachable(preferred, client)
                if (url2 != preferred) settings.setServerUrl(url2)
                val rpc = RpcClient(url2, client)
                val dshApi = DshApi(rpc)
                api = dshApi
                baseUrl = url2
                _activeBaseUrl.value = url2
                wsClient = client
                _connected.value = true
                // Theme sync is cosmetic and independent of the lists below, so it
                // must not sit in the connect path.
                viewModelScope.launch { syncWebTheme() }
                // Baseline network + JSON decode off the main thread so the
                // connecting spinner keeps animating while the host is slow. The two
                // lists are independent, so fetch them concurrently: one round trip
                // less is the single biggest win when the link is a phone hotspot.
                val (wsItems, sessions, archived) = withContext(Dispatchers.Default) {
                    val wsAsync = async { dshApi.workspaceList() }
                    val sessions = dshApi.sessionList()
                    val ws = wsAsync.await()
                    Triple(ws.items, sessions, ws.archivedSessionIds.toSet())
                }
                _workspaces.value = wsItems
                _sessions.value = sessions
                _archivedSessionIds.value = archived + locallyArchived.value
                applyLocalAttachments()
                seedRunning(sessions)
                refreshSubagentChildren(sessions)
                // Only now that the list is on screen: starting the service is a
                // main-thread binder round trip and used to delay the first frame.
                startForegroundService()
                collectFrames(url2, client)
                startWatchdog()
                // Refresh the DeepSeek balance if a key is already saved.
                if (settings.deepseekApiKey.first().isNotBlank()) {
                    queryDeepseekBalance()
                }
            } catch (e: Exception) {
                _connected.value = false
                _error.value = e.message ?: "connection failed"
            } finally {
                _connecting.value = false
            }
        }
    }

    /**
     * Return the first base URL that answers.
     *
     * Candidates are probed **concurrently** with a plain GET of the shell page.
     * Walking them one by one with a 6 s timeout each is what made "连接中" sit
     * there for half a minute on a dead MagicDNS name; the whole probe is now
     * bounded by [PROBE_TIMEOUT_MS]. The pinned host still wins when it answers,
     * so a working custom endpoint is never displaced by a candidate.
     */
    private suspend fun probeReachable(
        preferred: String,
        client: okhttp3.OkHttpClient,
    ): String = withContext(Dispatchers.Default) {
        val ordered = (listOf(preferred) + SettingsStore.CANDIDATE_URLS).distinct()
        val results = supervisorScope {
            ordered.map { url ->
                async {
                    url to withTimeoutOrNull(PROBE_TIMEOUT_MS) { responds(url, client) }
                }
            }.map { it.await() }
        }
        // `ordered` keeps the caller's preference first, so the first responder is
        // also the most preferred one.
        val winner: String? = results.firstOrNull { it.second == true }?.first
        if (winner != null) return@withContext winner
        throw java.io.IOException(
            "连接失败：${ordered.size} 个地址都无响应（" + ordered.joinToString("、") + "）"
        )
    }
    /** True when the address answers HTTP below 5xx (a 5xx means the upstream is dead). */
    private fun responds(url: String, client: okhttp3.OkHttpClient): Boolean = try {
        val req = okhttp3.Request.Builder().url(url.trimEnd('/') + "/").get().build()
        client.newCall(req).execute().use { it.code in 100..499 }
    } catch (_: Exception) {
        false
    }
    private var watchdogJob: Job? = null

    /** Last event timestamp per session, so other sessions cannot mask a stall. */
    private val lastEventAtBySession = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Background watchdog for a silent stream.
     *
     * On cellular a half-open socket can swallow the end of a turn: the host
     * finishes, the phone hears nothing, and the result only shows up after coming
     * back to a stable network. While a turn is supposed to be running, a stream
     * that has been quiet for 30 s is treated as dead: reconnect, ask the host for
     * the real state, and reload the conversation if it finished meanwhile.
     */
    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = viewModelScope.launch {
            while (true) {
                delay(15_000)
                try {
                    if (!_running.value) continue
                    val mine = _currentSessionId.value?.let { lastEventAtBySession[it] } ?: lastFrameAt
                    if (System.currentTimeMillis() - mine < 30_000) continue
                    val url = baseUrl
                    val client = wsClient
                    if (url != null && client != null) collectFrames(url, client, force = true)
                    refreshRunningStatus()
                    if (!_running.value && !_loadingSession.value) {
                        _currentSessionId.value?.let { openSession(it) }
                    }
                } catch (_: Exception) {
                }
            }
        }
    }
    private fun collectFrames(url: String, client: okhttp3.OkHttpClient, force: Boolean = false) {
        // WsClient.frames() already reconnects with backoff, so rebuilding the
        // streams every time the app resumes only produced connect/disconnect
        // churn. Rebuild only for a new endpoint, or when explicitly asked AND
        // the existing streams have gone silent (a half-open socket survives a
        // background trip without ever firing onFailure).
        val stillStreaming = url == framesUrl &&
            muxJob?.isActive == true && hostJob?.isActive == true
        if (stillStreaming) {
            if (!force) return
            if (System.currentTimeMillis() - lastFrameAt < STALE_STREAM_MS) return
        }
        muxJob?.cancel()
        hostJob?.cancel()
        framesUrl = url
        val ws = WsClient(url, client)
        muxJob = viewModelScope.launch {
            ws.frames("/api/events.mux").collect { frame ->
                lastFrameAt = System.currentTimeMillis()
                val type = frame.payload["type"]?.jsonPrimitive?.content ?: return@collect
                val sessionId = frame.payload["sessionId"]?.jsonPrimitive?.content
                when (type) {
                    "session/event" -> if (sessionId == _currentSessionId.value) {
                        val ev = json.decodeFromJsonElement<SessionEvent>(frame.payload["event"] ?: return@collect)
                        appendLiveEvent(ev)
                    }
                    "question/requested" -> {
                        val questions = json.decodeFromJsonElement<List<QuestionItem>>(
                            frame.payload["questions"] ?: return@collect
                        )
                        if (_pendingQuestions.value.none { it.rpcId == frame.rpcId }) {
                            _pendingQuestions.value = _pendingQuestions.value + PendingQuestion(
                                rpcId = frame.rpcId,
                                sessionId = sessionId ?: "",
                                questions = questions,
                            )
                            // Jump to the session that needs the decision so the
                            // plan-review / ask-user card has its conversation behind it.
                            if (sessionId != null && sessionId != _currentSessionId.value) openSession(sessionId)
                        }
                    }
                    "approval/requested" -> {
                        if (_pendingApprovals.value.none { it.rpcId == frame.rpcId }) {
                            _pendingApprovals.value = _pendingApprovals.value + PendingApproval(
                                rpcId = frame.rpcId,
                                sessionId = sessionId ?: "",
                                approvalId = frame.payload["approvalId"]?.jsonPrimitive?.content ?: "",
                                toolName = frame.payload["toolName"]?.jsonPrimitive?.content ?: "",
                                reason = frame.payload["reason"]?.jsonPrimitive?.content,
                            )
                            if (sessionId != null && sessionId != _currentSessionId.value) openSession(sessionId)
                        }
                    }
                    "question/resolved" -> {
                        val qRpcId = frame.payload["questionRpcId"]?.jsonPrimitive?.content
                        if (qRpcId != null) {
                            _pendingQuestions.value = _pendingQuestions.value.filterNot { it.rpcId == qRpcId }
                        }
                    }
                    "approval/resolved" -> {
                        val approvalId = frame.payload["approvalId"]?.jsonPrimitive?.content
                        if (approvalId != null) {
                            _pendingApprovals.value = _pendingApprovals.value.filterNot { it.approvalId == approvalId }
                        }
                    }
                    "session/jobs" -> {
                        val jobsEl = frame.payload["jobs"]
                        val jobs = if (jobsEl != null) {
                            try { json.decodeFromJsonElement<List<JobView>>(jobsEl) } catch (_: Exception) { emptyList() }
                        } else emptyList()
                        if (sessionId != null) jobsBySession[sessionId] = jobs
                        if (sessionId == _currentSessionId.value) _jobs.value = jobs
                    }
                    "session/queue" -> {
                        val itemsEl = frame.payload["items"]
                        val items = if (itemsEl != null) {
                            try { json.decodeFromJsonElement<List<QueueItem>>(itemsEl) } catch (_: Exception) { emptyList() }
                        } else emptyList()
                        val pending = items.filter { it.placement == "queued" }
                        // The mux stream broadcasts every session's queue, so cache
                        // each one; a session switch can then show it without a refetch.
                        if (sessionId != null) queueBySession[sessionId] = pending
                        if (sessionId == _currentSessionId.value) _queueItems.value = pending
                    }
                    "session/projection" -> {
                        // Projections (todos, stats, goal, tokens, context…) change on
                        // every step of a turn, so this is throttled rather than
                        // pulling the 250 KB session list each time.
                        refreshSessionsDebounced(2_000L)
                        // Model selection is a projection too: without this a model
                        // switched from the Web UI never showed up on the phone.
                        refreshModelsDebounced()
                    }
                }
            }
        }
        hostJob = viewModelScope.launch {
            ws.frames("/api/events.host").collect { frame ->
                lastFrameAt = System.currentTimeMillis()
                val type = frame.payload["type"]?.jsonPrimitive?.content ?: return@collect
                when (type) {
                    "host/session-status" -> {
                        val sid = frame.payload["sessionId"]?.jsonPrimitive?.content
                        val isRunning = frame.payload["running"]?.jsonPrimitive?.content?.toBoolean() ?: false
                        if (sid != null) {
                            val wasRunning = runningBySession[sid]
                            runningBySession[sid] = isRunning
                            if (wasRunning == true && !isRunning && sid != _currentSessionId.value) {
                                // A conversation finished while not being watched.
                                _finishedSessions.value = _finishedSessions.value + (sid to System.currentTimeMillis())
                            }
                        }
                        if (sid == _currentSessionId.value) {
                            setRunning(isRunning)
                        }
                        refreshSessionsDebounced(1_000L)
                    }
                    "host/session-added", "host/session-removed" -> refreshSessionsDebounced(1_000L)
                    "host/workspace-changed", "host/workspace-removed",
                    "host/workspace-order-changed", "host/archived-sessions-changed" -> refreshAll()
                }
            }
        }
    }
    private fun appendLiveEvent(ev: SessionEvent) {
        val sessionId = _currentSessionId.value ?: return
        // De-duplicate: drop events already seen in the loaded history or delivered twice
        // (e.g. when the WS reconnect replays a frame).
        lastEventAtBySession[sessionId] = System.currentTimeMillis()
        val lastSeq = lastSeqBySession[sessionId] ?: 0L
        if (ev.seq <= lastSeq) return
        lastSeqBySession[sessionId] = ev.seq
        // assistant/chunk events are transient streaming deltas — process them but do not
        // store them (they are the most numerous and would bloat memory over a long session).
        if (ev.type == "assistant/chunk") {
            val chunk = ev.data["chunk"]?.jsonObject
            val chunkType = chunk?.get("type")?.jsonPrimitive?.content
            val text = chunk?.get("text")?.jsonPrimitive?.content ?: ""
            when (chunkType) {
                "text-delta" -> if (text.isNotEmpty()) _streaming.value = _streaming.value + text
                "reasoning-delta" -> if (text.isNotEmpty()) _streamingReasoning.value = _streamingReasoning.value + text
            }
            return
        }
        _events.value = (_events.value + ev).takeLast(MAX_EVENTS)
        when (ev.type) {
            "assistant/message" -> {
                val msg = ev.data["message"]?.jsonObject
                val content = msg?.get("content")
                val text = textOf(content)
                val reasoningSummary = reasoningSummaryOf(content)
                val images = imagesOf(content)
                if (text.isNotBlank() || reasoningSummary.isNotBlank() || images.isNotEmpty()) {
                    _chatItems.value = _chatItems.value + ChatItem.Assistant(text, ev.seq, reasoningSummary, images = images)
                }
                _currentSessionId.value?.let { sid -> images.forEach { loadImage(sid, it) } }
                // Accumulate per-turn token usage for the cost line.
                ev.data["usage"]?.jsonObject?.let { u ->
                    val usage = TurnUsage(
                        inputTokens = u["inputTokens"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
                        cacheReadTokens = u["cacheReadTokens"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
                        outputTokens = u["outputTokens"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
                    )
                    turnUsage = turnUsage?.plus(usage) ?: usage
                    _turnUsageFlow.value = turnUsage
                }
                _streaming.value = ""
                _streamingReasoning.value = ""
            }
            "turn/start" -> {
                turnUsage = null
                _turnUsageFlow.value = null
                _streaming.value = ""
                _streamingReasoning.value = ""
                // The authoritative start of this turn — this is what the whale
                // counts from, so a turn begun elsewhere also gets a real start.
                _currentSessionId.value?.let { sid ->
                    runStartBySession[sid] = System.currentTimeMillis()
                    _runStartedAt.value = runStartBySession[sid]
                }
            }
            "turn/end" -> {
                _streaming.value = ""
                _streamingReasoning.value = ""
                // Clear the spinner here too: if the session-status frame is lost the
                // turn otherwise looked like it kept running forever.
                runningBySession[sessionId] = false
                if (sessionId == _currentSessionId.value) setRunning(false)
                // Mark the final assistant message of the turn so fork / copy
                // actions render only after the turn's last output.
                val items = _chatItems.value
                val idx = items.indexOfLast { it is ChatItem.Assistant }
                if (idx >= 0) {
                    val a = items[idx] as ChatItem.Assistant
                    if (!a.isTurnEnd) {
                        _chatItems.value = items.toMutableList().also { it[idx] = a.copy(isTurnEnd = true) }
                    }
                }
                // Show the real cost of this turn from the balance difference;
                // fall back to a token estimate when balance isn't available.
                val tu = turnUsage
                turnUsage = null
                queryTurnCost(tu)
            }
            "tool/call" -> {
                val folded = foldChat(listOf(ev))
                if (folded.isNotEmpty()) _chatItems.value = _chatItems.value + folded
            }
            "tool/result" -> {
                val resultItem = foldChat(listOf(ev)).firstOrNull() as? ChatItem.Tool
                val last = _chatItems.value.lastOrNull()
                if (last is ChatItem.Tool && !last.isResult && resultItem != null) {
                    // Merge the live call + result into a single card.
                    _chatItems.value = _chatItems.value.dropLast(1) + last.copy(
                        isResult = true,
                        isError = resultItem.isError,
                        arguments = resultItem.arguments.ifBlank { last.arguments },
                        card = resultItem.card ?: last.card,
                        summary = resultItem.summary ?: last.summary,
                        diffPath = resultItem.diffPath ?: last.diffPath,
                        diffOld = resultItem.diffOld ?: last.diffOld,
                        diffNew = resultItem.diffNew ?: last.diffNew,
                    )
                } else if (resultItem != null) {
                    _chatItems.value = _chatItems.value + resultItem
                }
            }
            else -> {
                val folded = foldChat(listOf(ev))
                if (folded.isNotEmpty()) {
                    _chatItems.value = _chatItems.value + folded
                    // A live user/message (image) reaches this branch; kick off
                    // image loading here too, otherwise the thumbnail stays on
                    // its "loading" placeholder until the session is reopened.
                    _currentSessionId.value?.let { sid -> loadImagesFor(folded, sid) }
                }
            }
        }
    }
    /**
     * Load one chat image and cache the full-resolution bitmap by attachmentId.
     * The cached bitmap is NOT a tiny thumbnail: tapping it opens the original
     * (DSH normalizes stored images to a 2048px long edge), so decoding below
     * that ceiling keeps the tap-to-zoom view sharp.
     *
     * Retries with backoff: the durable attachment may not be published yet when
     * a live user/message first arrives, so a transient miss is retried rather
     * than leaving the thumbnail on its "loading" placeholder forever.
     */
    private fun loadImage(sessionId: String, ref: ImageRef, attempt: Int = 0) {
        if (ref.attachmentId.isEmpty()) return
        if (_imageCache.value.containsKey(ref.attachmentId)) return
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                try {
                    api?.sessionAttachment(sessionId, ref.attachmentId)
                } catch (_: Exception) {
                    null
                }
            }
            val bmp = if (bytes != null) decodeImage(bytes.base64, maxDim = 2048) else null
            if (bmp != null) {
                // Only cache if still relevant (session may have switched meanwhile).
                if (_currentSessionId.value == sessionId) {
                    _imageCache.value = _imageCache.value + (ref.attachmentId to bmp)
                }
            } else if (attempt < 6) {
                // Durable image not ready yet — back off and retry.
                val delay = longArrayOf(1500L, 2000L, 3000L, 5000L, 8000L, 12000L)[attempt]
                delay(delay)
                loadImage(sessionId, ref, attempt + 1)
            }
        }
    }
    /** Kick off image loading for every image referenced by the given chat items. */
    private fun loadImagesFor(items: List<ChatItem>, sessionId: String) {
        for (item in items) {
            val refs = when (item) {
                is ChatItem.User -> item.images
                is ChatItem.Assistant -> item.images
                else -> emptyList()
            }
            refs.forEach { loadImage(sessionId, it) }
        }
    }
    /** base64 → Bitmap decode (bounded long edge, off the main thread). */
    private fun decodeImage(base64: String, maxDim: Int = 2048): Bitmap? {
        return try {
            val bytes = Base64.decode(base64, Base64.NO_WRAP)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
            while (maxSide / sample > maxDim) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (_: Exception) {
            null
        }
    }
    fun openSession(sessionId: String) {
        viewModelScope.launch {
            // Show the loading spinner immediately, before any wait.
            _loadingSession.value = true
            ensureDraftsLoaded()
            // Save the outgoing session's draft before switching.
            _currentSessionId.value?.let { old ->
                if (old != sessionId) {
                    if (_draft.value.isNotBlank()) draftBySession[old] = _draft.value
                    else draftBySession.remove(old)
                }
            }
            _currentSessionId.value = sessionId
            _finishedSessions.value = _finishedSessions.value - sessionId
            _draft.value = draftBySession[sessionId] ?: ""
            setRunning(runningBySession[sessionId] ?: false)
            // Re-query the authoritative running state so a background-completed
            // turn is not left showing "运行中".
            refreshRunningStatus()
            _chatItems.value = emptyList()
            _events.value = emptyList()
            _imageCache.value = emptyMap()
            _queueItems.value = queueBySession[sessionId] ?: emptyList()
            _jobs.value = jobsBySession[sessionId] ?: emptyList()
            _streaming.value = ""
            _streamingReasoning.value = ""
            _hasMore.value = false
            _loadingOlder.value = false
            _oldestSeq = null
            _fullTimelineSpans.value = emptyList()
            fullTimelineSessionId = null
            lastSeqBySession[sessionId] = 0
            try {
                // Network + JSON decode + fold off the main thread so the spinner spins.
                // Bounded so a hung RPC (e.g. a transient 403/network stall) can never
                // leave the chat stuck on the loading spinner forever: after the cap we
                // clear loading and let the live WS stream show what it already has.
                // A running session needs a wider window: the whale counts from the
                // turn's own `turn/start` event, and 15 messages is often not enough
                // to reach it once the turn has produced a few tool calls.
                val historyWindow = if (runningBySession[sessionId] == true) 200 else 15
                val history = withTimeoutOrNull(20_000) {
                    withContext(Dispatchers.Default) {
                        api?.sessionHistory(sessionId, maxMessages = historyWindow)
                    }
                }
                // The user may have switched sessions while loading — only apply if still current.
                if (history != null && _currentSessionId.value == sessionId) {
                    val events = history.events.map { it.event }
                    val views = history.events.mapNotNull { e -> e.view?.let { e.event.seq to it } }.toMap()
                    val folded = withContext(Dispatchers.Default) { foldChat(events, views) }
                    _events.value = compactEvents(events)
                    _chatItems.value = folded
                    _hasMore.value = history.hasMore
                    events.firstOrNull()?.let { _oldestSeq = it.seq }
                    events.lastOrNull()?.let { lastSeqBySession[sessionId] = it.seq }
                    // Connected in the middle of a turn: recover its real start from
                    // the host's own event timestamps. Only the *elapsed* span is
                    // used, so a clock difference between phone and host cancels out.
                    if (runningBySession[sessionId] == true && runStartBySession[sessionId] == null) {
                        val turnStart = events.lastOrNull { it.type == "turn/start" }?.time ?: 0L
                        val latest = events.lastOrNull()?.time ?: 0L
                        val elapsed = latest - turnStart
                        // The page is capped at 15 messages, so a very long turn pushes
                        // its turn/start out of the window and the newest one may belong
                        // to an older turn. Beyond a plausible span, show no number
                        // instead of a wrong one.
                        if (turnStart > 0L && elapsed >= 0L && elapsed <= 6 * 3_600_000L) {
                            runStartBySession[sessionId] = System.currentTimeMillis() - elapsed
                            if (_currentSessionId.value == sessionId) {
                                _runStartedAt.value = runStartBySession[sessionId]
                            }
                        }
                    }
                    loadImagesFor(folded, sessionId)
                }
                refreshModels()
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _loadingSession.value = false
            }
        }
    }
    /** Open a session after the connection is established (notification deep-link entry). */
    fun openSessionWhenReady(sessionId: String) {
        viewModelScope.launch {
            if (!_connected.value) {
                var guard = 0
                while (!_connected.value && guard < 150) { delay(100); guard++ }
            }
            openSession(sessionId)
        }
    }
    fun fullReasoning(seq: Long): String {
        val ev = _events.value.firstOrNull { it.seq == seq } ?: return ""
        val msg = ev.data["message"]?.jsonObject
        return reasoningOf(msg?.get("content"))
    }
    /** Lazily fetch the full (paginated) history to draw the complete trajectory timeline. */
    fun ensureFullTimeline(sessionId: String) {
        if (fullTimelineSessionId == sessionId) return
        fullTimelineSessionId = sessionId
        viewModelScope.launch {
            try {
                val spans = ArrayList<TimelineSpan>()
                var beforeSeq: Long? = null
                var guard = 0
                while (guard++ < 500) {
                    val history = withContext(Dispatchers.Default) {
                        api?.sessionHistory(sessionId, beforeSeq = beforeSeq, maxMessages = 100)
                    } ?: break
                    val events = history.events.map { it.event }
                    if (events.isEmpty()) break
                    // Newer chunks are already in `spans`; prepend this older chunk.
                    spans.addAll(0, timelineSpansOf(events))
                    if (!history.hasMore) break
                    beforeSeq = events.firstOrNull()?.seq
                }
                _fullTimelineSpans.value = spans
            } catch (_: Exception) {
            }
        }
    }
    fun loadOlder() {
        val sessionId = _currentSessionId.value ?: return
        if (_loadingOlder.value || !_hasMore.value) return
        val beforeSeq = _oldestSeq ?: return
        _loadingOlder.value = true
        viewModelScope.launch {
            try {
                val history = withContext(Dispatchers.Default) {
                    api?.sessionHistory(sessionId, beforeSeq = beforeSeq, maxMessages = 15)
                }
                if (history != null && _currentSessionId.value == sessionId) {
                    val older = history.events.map { it.event }
                    if (older.isNotEmpty()) {
                        val views = history.events.mapNotNull { e -> e.view?.let { e.event.seq to it } }.toMap()
                        val folded = withContext(Dispatchers.Default) { foldChat(older, views) }
                        _events.value = compactEvents(older) + _events.value
                        _chatItems.value = folded + _chatItems.value
                        loadImagesFor(folded, sessionId)
                        older.firstOrNull()?.let { _oldestSeq = it.seq }
                    }
                    _hasMore.value = history.hasMore
                }
            } catch (_: Exception) {
            } finally {
                _loadingOlder.value = false
            }
        }
    }
    fun send(text: String) {
        val sessionId = _currentSessionId.value ?: return
        val textTrim = text.trim()
        if (textTrim.isEmpty()) return
        sendInternal(sessionId, textTrim)
    }
    private fun sendInternal(sessionId: String, text: String) {
        _draft.value = ""
        draftBySession.remove(sessionId)
        persistDrafts()
        if (isCommandLine(text)) {
            // Slash commands execute through the host command channel, not as chat text.
            viewModelScope.launch { runCommand(sessionId, text) }
        } else {
            startRunNow()
            runningBySession[sessionId] = true
            viewModelScope.launch {
                try {
                    api?.sessionPrompt(sessionId, text)
                } catch (e: Exception) {
                    _error.value = e.message
                }
            }
        }
    }
    /** Whether a message starts with a slash-command token (e.g. "/plan"). */
    private fun isCommandLine(text: String): Boolean =
        COMMAND_LINE_REGEX.containsMatchIn(text)
    /** Execute a slash command and surface its result; fall back to a plain prompt for unknown commands. */
    private suspend fun runCommand(sessionId: String, line: String) {
        try {
            val r = api?.commandExecute(sessionId, line)
            if (r == null) {
                // Unknown/malformed command — send it to the model instead of dropping it.
                startRunNow()
                runningBySession[sessionId] = true
                api?.sessionPrompt(sessionId, line)
                return
            }
            when (r.result.kind) {
                "error" -> {
                    _error.value = r.result.text ?: Strings.str("command_failed")
                    r.result.text?.let { toast(it) }
                }
                else -> {
                    r.result.text?.takeIf { it.isNotBlank() }?.let { toast(it) }
                }
            }
            // Refresh so plan/permission/goal projections (and any steer-opened turn) reflect.
            refreshSessions()
        } catch (e: Exception) {
            _error.value = e.message
            toast(Strings.str("command_failed_fmt", e.message))
        }
    }
    /** Send an image (base64 data) as a user prompt. */
    fun sendImage(sessionId: String, mediaType: String, base64: String, name: String?) {
        startRunNow()
        runningBySession[sessionId] = true
        viewModelScope.launch {
            try {
                api?.sessionPromptImage(sessionId, mediaType, base64, name)
            } catch (e: Exception) {
                _error.value = e.message
                toast(attachmentErrorMessage(e))
            }
        }
    }
    /** Send an image + text together. */
    fun sendImageText(sessionId: String, mediaType: String, base64: String, name: String?, text: String) {
        startRunNow()
        runningBySession[sessionId] = true
        _draft.value = ""
        draftBySession.remove(sessionId)
        persistDrafts()
        viewModelScope.launch {
            try {
                api?.sessionPromptImageText(sessionId, mediaType, base64, name, text)
            } catch (e: Exception) {
                _error.value = e.message
                toast(attachmentErrorMessage(e))
            }
        }
    }
    /** Send multiple images (+ optional text) together as one user prompt. */
    fun sendImagesText(sessionId: String, images: List<ImageData>, text: String) {
        startRunNow()
        runningBySession[sessionId] = true
        _draft.value = ""
        draftBySession.remove(sessionId)
        persistDrafts()
        viewModelScope.launch {
            try {
                api?.sessionPromptImages(sessionId, images, text)
            } catch (e: Exception) {
                _error.value = e.message
                toast(attachmentErrorMessage(e))
            }
        }
    }
    /** Turn an attachment rejection into a short, human-readable toast message. */
    private fun attachmentErrorMessage(e: Exception): String {
        val msg = e.message ?: ""
        return when {
            msg.contains("does not support image", ignoreCase = true) ||
                msg.contains("MODEL_DOES_NOT_SUPPORT_IMAGES", ignoreCase = true) ->
                Strings.str("image_unsupported")
            else -> Strings.str("attachment_failed_fmt", msg)
        }
    }
    /**
     * Queue a message by forwarding it to the host immediately. The host's own
     * inbox natively queues it while a turn runs, so it is sent in order and —
     * crucially — does not depend on the app staying foregrounded: the message is
     * already on the server the moment you tap send.
     */
    fun enqueue(text: String) {
        val sessionId = _currentSessionId.value ?: return
        val t = text.trim()
        if (t.isEmpty()) return
        sendInternal(sessionId, t)
    }
    fun removeQueued(itemId: String) {
        val sessionId = _currentSessionId.value ?: return
        viewModelScope.launch {
            try { api?.sessionUpdateQueue(sessionId, itemId, "remove") }
            catch (e: Exception) { _error.value = e.message }
        }
    }
    fun editQueued(itemId: String, text: String) {
        val sessionId = _currentSessionId.value ?: return
        val t = text.trim()
        viewModelScope.launch {
            try {
                if (t.isEmpty()) api?.sessionUpdateQueue(sessionId, itemId, "remove")
                else api?.sessionUpdateQueue(sessionId, itemId, "edit", t)
            } catch (e: Exception) { _error.value = e.message }
        }
    }
    fun cancel() {
        val sessionId = _currentSessionId.value ?: return
        viewModelScope.launch {
            try { api?.sessionCancel(sessionId) } catch (_: Exception) {}
        }
    }
    fun selectModel(sessionId: String, provider: String, model: String, effort: String? = null) {
        viewModelScope.launch {
            try {
                val dsh = api
                if (dsh == null) {
                    toast("未连接，无法切换模型")
                    return@launch
                }
                dsh.sessionSelectModel(sessionId, provider, model, effort)
                refreshModels(force = true)
            } catch (e: Exception) {
                toast("切换模型失败: ${e.message ?: "unknown"}")
            }
        }
    }
    /** Create a session, preferring [workspaceId] when the caller picked one. */
    fun newSession(workspaceId: String? = null) {
        viewModelScope.launch {
            try {
                val dsh = api
                if (dsh == null) {
                    toast("未连接，无法新建会话")
                    return@launch
                }
                // The workspace list can be empty right after launch (the bridge's
                // workspace snapshot may not have arrived yet). Pull it first, else
                // the new session is created without a workspace and never shows up
                // under one — which made creation look like it had failed.
                if (_workspaces.value.isEmpty()) {
                    try {
                        val ws = dsh.workspaceList()
                        _workspaces.value = ws.items
                        _archivedSessionIds.value = ws.archivedSessionIds.toSet() + locallyArchived.value
                        applyLocalAttachments()
                    } catch (_: Exception) {
                        // fall through: the guard below reports it
                    }
                }
                val currentId = _currentSessionId.value
                val target = workspaceId?.let { id -> _workspaces.value.firstOrNull { it.workspaceId == id } }
                    ?: _workspaces.value.firstOrNull { currentId != null && currentId in it.sessionIds }
                    ?: _workspaces.value.firstOrNull()
                if (target == null) {
                    toast("没有可用的工作区，请先新建工作区")
                    return@launch
                }
                val created = dsh.sessionCreate(workspaceId = target.workspaceId)
                // Attach it explicitly: create-with-workspaceId alone does not add
                // the session to workspace.sessionIds in dsh 0.2.x.
                var attached = false
                try {
                    dsh.workspaceInsertSessionBefore(target.workspaceId, created.sessionId)
                    attached = true
                } catch (e: Exception) {
                    _error.value = "归属工作区失败: ${e.message}"
                }
                // Attest the attachment locally: the host snapshot trails the attach
                // call, so a later refresh would otherwise drop it back into the
                // "ungrouped" bucket.
                locallyAttached[created.sessionId] = target.workspaceId
                applyLocalAttachments()
                refreshSessions()
                openSession(created.sessionId)
                toast(if (attached) "已新建会话" else "已新建会话（未归属）")
            } catch (e: Exception) {
                _error.value = e.message
                toast("新建会话失败: ${e.message ?: "unknown"}")
            }
        }
    }
    private fun toast(msg: String) {
        try {
            android.widget.Toast.makeText(getApplication<Application>(), msg, android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}
    }
    /** Branch the conversation into a new child session, anchored at the chosen message. */
    fun forkSession(atSeq: Long? = null) {
        val sessionId = _currentSessionId.value ?: return
        viewModelScope.launch {
            try {
                val created = api?.sessionFork(sessionId, atSeq)
                if (created != null) {
                    refreshSessions()
                    openSession(created.sessionId)
                    toast(Strings.str("forked_session"))
                } else {
                    toast(Strings.str("fork_failed_not_conn"))
                }
            } catch (e: Exception) {
                _error.value = e.message
                toast(Strings.str("fork_failed_fmt", e.message))
            }
        }
    }
    /** Copy a single message's text to the clipboard. */
    fun copyText(text: String) {
        val t = text.trim()
        if (t.isEmpty()) {
            toast(Strings.str("no_copy_content"))
            return
        }
        val cm = getApplication<Application>()
            .getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("message", t))
        toast(Strings.str("copied"))
    }
    /** Copy the visible conversation text to the clipboard. */
    fun copyConversation() {
        val text = _chatItems.value.joinToString("\n\n") { item ->
            when (item) {
                is ChatItem.User -> Strings.str("you_prefix", item.text)
                is ChatItem.Assistant -> item.text
                else -> ""
            }
        }.trim()
        if (text.isEmpty()) {
            toast(Strings.str("no_copy_content"))
            return
        }
        val cm = getApplication<Application>()
            .getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("conversation", text))
        toast(Strings.str("copied_clipboard"))
    }
    /** Archive (remove) the current conversation. */
    fun archiveSession() {
        val sessionId = _currentSessionId.value ?: return
        deleteSessionById(sessionId)
    }
    /** Return to the home screen without deleting the current conversation. */
    fun goHome() {
        val old = _currentSessionId.value
        if (old != null && _draft.value.isNotBlank()) {
            draftBySession[old] = _draft.value
            persistDrafts()
        }
        _currentSessionId.value = null
        _chatItems.value = emptyList()
        _events.value = emptyList()
        _imageCache.value = emptyMap()
        _streaming.value = ""
        _streamingReasoning.value = ""
        _draft.value = ""
        _fullTimelineSpans.value = emptyList()
        fullTimelineSessionId = null
    }
    fun deleteSessionById(sessionId: String) {
        viewModelScope.launch {
            try {
                api?.archiveSession(sessionId)
                // Hide it immediately: the host workspace snapshot lags behind the
                // archive call, so waiting for refreshAll() made deletion look broken.
                locallyArchived.value = locallyArchived.value + sessionId
                _archivedSessionIds.value = _archivedSessionIds.value + sessionId
                if (_currentSessionId.value == sessionId) {
                    _currentSessionId.value = null
                    _chatItems.value = emptyList()
                    _events.value = emptyList()
                    _streaming.value = ""
                    _streamingReasoning.value = ""
                    _draft.value = ""
                    draftBySession.remove(sessionId)
                }
                refreshAll()
                toast(Strings.str("deleted_session"))
            } catch (e: Exception) {
                _error.value = e.message
                toast(Strings.str("delete_failed_fmt", e.message))
            }
        }
    }
    fun renameSession(sessionId: String, title: String) {
        viewModelScope.launch {
            try {
                api?.sessionRename(sessionId, title)
                refreshSessions()
            } catch (_: Exception) {}
        }
    }
    fun togglePlan(sessionId: String, active: Boolean) {
        viewModelScope.launch {
            runCommand(sessionId, if (active) "/plan off" else "/plan")
        }
    }
    fun setPermission(sessionId: String, name: String) {
        viewModelScope.launch {
            runCommand(sessionId, "/permission $name")
        }
    }
    /** Execute an arbitrary slash command through the host command channel. */
    fun sendCommand(sessionId: String, command: String) {
        viewModelScope.launch {
            runCommand(sessionId, command)
        }
    }
    private fun currentGoalRef(): Pair<String, Int>? {
        val g = currentGoal.value?.goal ?: return null
        return g.id to g.revision
    }
    fun goalPause() {
        val sid = _currentSessionId.value ?: return
        val ref = currentGoalRef() ?: return
        viewModelScope.launch {
            try { api?.goalPause(sid, ref.first, ref.second); refreshSessions() } catch (_: Exception) {}
        }
    }
    fun goalResume() {
        val sid = _currentSessionId.value ?: return
        val ref = currentGoalRef() ?: return
        viewModelScope.launch {
            try { api?.goalResume(sid, ref.first, ref.second); refreshSessions() } catch (_: Exception) {}
        }
    }
    fun goalComplete() {
        val sid = _currentSessionId.value ?: return
        val ref = currentGoalRef() ?: return
        viewModelScope.launch {
            try { api?.goalComplete(sid, ref.first, ref.second); refreshSessions() } catch (_: Exception) {}
        }
    }
    fun goalCreate(objective: String) {
        val sid = _currentSessionId.value ?: return
        if (objective.isBlank()) return
        viewModelScope.launch {
            try { api?.goalCreate(sid, objective); refreshSessions() } catch (_: Exception) {}
        }
    }
    /** Last model-catalog fetch, so projection ticks cannot hammer this endpoint. */
    private var lastModelsAt = 0L
    private var lastModelsSessionId: String? = null
    private fun refreshModels(force: Boolean = false) {
        // No session yet is fine: the catalogue itself is session-independent, and
        // requiring one meant newly installed plugin providers never showed up.
        val sessionId = _currentSessionId.value
        val now = System.currentTimeMillis()
        // The projection stream ticks many times per turn and the catalog is a
        // large payload; refetching it on every tick made the model menu feel slow.
        // Debounced callers already throttle; the 5s cache would otherwise swallow them.
        if (!force && sessionId == lastModelsSessionId && now - lastModelsAt < 5_000L) return
        lastModelsAt = now
        lastModelsSessionId = sessionId
        viewModelScope.launch {
            try { _models.value = api?.sessionModels(sessionId) } catch (_: Exception) {}
        }
    }
    private fun seedRunning(list: List<SessionSummary>) {
        for (s in list) runningBySession[s.sessionId] = s.running
    }
    /** Drop transient assistant/chunk events (the bulk of the log) before storing history. */
    private fun compactEvents(events: List<SessionEvent>): List<SessionEvent> =
        events.filter { it.type != "assistant/chunk" }
    /** Re-query the host for the current session's real running state (used on resume / open). */
    fun refreshRunningStatus() {
        val sessionId = _currentSessionId.value ?: return
        viewModelScope.launch {
            try {
                val list = withContext(Dispatchers.Default) { api?.sessionList() } ?: return@launch
                _sessions.value = list
                seedRunning(list)
                val cur = _currentSessionId.value
                if (cur != null) {
                    setRunning(runningBySession[cur] ?: false)
                }
            } catch (_: Exception) {}
        }
    }
    /** Called when the app returns to the foreground: force-fresh streams + catch up. */
    fun onAppResumed() {
        // Re-establish the WebSocket streams; a backgrounded app can hold a half-open
        // socket that never errors, so retryWhen never re-subscribes on its own.
        // collectFrames() rebuilds only if the streams have actually gone silent.
        val url = baseUrl
        val client = wsClient
        if (url != null && client != null) collectFrames(url, client, force = true)
        // A configuration change recreates the activity and fires ON_RESUME again
        // right away; re-pulling the whole session list for that only added lag.
        val now = System.currentTimeMillis()
        if (now - lastResumeRefreshAt < 3_000L) return
        lastResumeRefreshAt = now
        viewModelScope.launch {
            try {
                // Always refresh the session + workspace list on resume.
                val list = withContext(Dispatchers.Default) { api?.sessionList() }
                if (list != null) {
                    _sessions.value = list
                    seedRunning(list)
                }
                val ws = withContext(Dispatchers.Default) { api?.workspaceList() }
                if (ws != null) {
                    _workspaces.value = ws.items
                    applyLocalAttachments()
                }
                val sessionId = _currentSessionId.value ?: return@launch
                // Authoritative running state for the open session.
                setRunning(runningBySession[sessionId] ?: false)
                // Recover events that arrived while the socket was dead.
                val history = withContext(Dispatchers.Default) {
                    api?.sessionHistory(sessionId, maxMessages = 30)
                }
                if (history != null && _currentSessionId.value == sessionId) {
                    val lastSeq = lastSeqBySession[sessionId] ?: 0L
                    val fresh = history.events.map { it.event }.filter { it.seq > lastSeq }
                    if (fresh.isNotEmpty()) {
                        val folded = foldChat(fresh)
                        _events.value = (_events.value + compactEvents(fresh)).takeLast(MAX_EVENTS)
                        _chatItems.value = _chatItems.value + folded
                        loadImagesFor(folded, sessionId)
                        lastSeqBySession[sessionId] = fresh.last().seq
                        _streaming.value = ""
                        _streamingReasoning.value = ""
                    }
                }
            } catch (_: Exception) {}
        }
    }
    private var modelsRefreshJob: Job? = null

    /** Refresh the model catalogue at most once every few seconds. */
    private fun refreshModelsDebounced() {
        if (modelsRefreshJob?.isActive == true) return
        modelsRefreshJob = viewModelScope.launch {
            delay(2_500L)
            try { refreshModels(force = true) } catch (_: Exception) {}
        }
    }
    private var sessionsRefreshJob: Job? = null
    /** Guards the resume refresh against activity-recreate churn. */
    private var lastResumeRefreshAt = 0L
    /**
     * Coalesce host notifications into one `session.list` round trip.
     *
     * That RPC returns the whole session list (~250 KB over the tailnet) and is
     * decoded on the main thread's dispatcher, so firing it per frame made the app
     * feel laggy during a run — `session/projection` alone arrives on every step.
     */
    private fun refreshSessionsDebounced(delayMs: Long = 1_500L) {
        // Leading-edge: do NOT cancel a pending refresh. Projection frames arrive
        // continuously during a run, so cancelling each time starved the refresh and
        // left session titles frozen for the whole turn.
        if (sessionsRefreshJob?.isActive == true) return
        sessionsRefreshJob = viewModelScope.launch {
            delay(delayMs)
            try {
                val list = api?.sessionList() ?: return@launch
                _sessions.value = list
                seedRunning(list)
                refreshSubagentChildren(list)
            } catch (_: Exception) {
            }
        }
    }
    private fun refreshSessions() {
        viewModelScope.launch {
            try {
                val list = api?.sessionList() ?: emptyList()
                _sessions.value = list
                seedRunning(list)
                refreshSubagentChildren(list)
            } catch (_: Exception) {}
        }
    }
    /** Fetch each subagent parent's catalog so the sidebar can keep continuable children. */
    private fun refreshSubagentChildren(list: List<SessionSummary>) {
        val parents = list.filter { it.isSubagent && it.parentSessionId != null }
            .mapNotNull { it.parentSessionId }
            .toSet()
        if (parents.isEmpty()) {
            _subagentChildrenByParent.value = emptyMap()
            return
        }
        viewModelScope.launch {
            val map = mutableMapOf<String, List<SubagentEntry>>()
            for (p in parents) {
                try {
                    val entries = withContext(Dispatchers.Default) { api?.subagentList(p) }?.entries ?: emptyList()
                    map[p] = entries
                } catch (_: Exception) {
                    map[p] = emptyList()
                }
            }
            _subagentChildrenByParent.value = map
        }
    }
    private fun refreshAll() {
        viewModelScope.launch {
            try {
                val ws = api?.workspaceList()
                if (ws != null) {
                    _workspaces.value = ws.items
                    _archivedSessionIds.value = ws.archivedSessionIds.toSet() + locallyArchived.value
                    applyLocalAttachments()
                }
                val list = api?.sessionList() ?: emptyList()
                _sessions.value = list
                seedRunning(list)
                refreshSubagentChildren(list)
            } catch (_: Exception) {}
        }
    }
    fun respondApproval(p: PendingApproval, allow: Boolean) {
        viewModelScope.launch {
            try {
                api?.respondApproval(p.rpcId, p.sessionId, p.approvalId, allow)
            } catch (_: Exception) {}
            _pendingApprovals.value = _pendingApprovals.value.filterNot { it.rpcId == p.rpcId }
        }
    }
    fun respondQuestion(p: PendingQuestion, answers: List<dev.dsh.remote.data.QuestionAnswer>) {
        viewModelScope.launch {
            try {
                api?.respondQuestion(p.rpcId, p.sessionId, answers)
            } catch (_: Exception) {}
            _pendingQuestions.value = _pendingQuestions.value.filterNot { it.rpcId == p.rpcId }
        }
    }
    // --- File browser ---
    /** Open the file browser at a directory (null → the current workspace, else home). */
    fun openFileBrowser(path: String? = null) {
        val start = path ?: _workspaces.value.firstOrNull()?.path
        listDir(start)
    }
    fun listDir(path: String?) {
        _dirLoading.value = true
        _dirError.value = null
        viewModelScope.launch {
            try {
                val listing = withContext(Dispatchers.Default) { api?.listDirectory(path) } ?: return@launch
                _dirPath.value = listing.path
                // A drive-selector screen (drives non-empty) has no parent; hide "up".
                val isDriveScreen = listing.drives.isNotEmpty()
                // A drive root (e.g. "C:\") can still go back up to the drive selector,
                // so treat it like a parent is available (target the selector = null).
                // Regex matches "X:" or "X:\" or "X:/" (a bare drive letter root).
                val isDriveRoot = Regex("^[A-Za-z]:[\\\\/]?$").matches(listing.path)
                _dirParent.value = when {
                    isDriveScreen -> null
                    isDriveRoot -> ""   // sentinel: up goes to drive selector
                    else -> listing.parent.takeIf { it.isNotBlank() && it != listing.path }
                }
                _dirEntries.value = listing.entries.ifEmpty { listing.drives }
            } catch (e: Exception) {
                _dirError.value = e.message ?: "list failed"
            } finally {
                _dirLoading.value = false
            }
        }
    }
    fun navigateUp() {
        val parent = _dirParent.value ?: return
        // "" sentinel means "at a drive root" -> go to the drive selector (null path).
        val target = if (parent.isEmpty()) null else parent
        listDir(target)
    }
    /** Download a file's bytes (called from the browser UI). */
    suspend fun downloadFile(path: String): ByteArray {
        return withContext(Dispatchers.Default) { api?.downloadFile(path) ?: ByteArray(0) }
    }
    /** Stream a file download to a local destination file. */
    suspend fun downloadFileTo(path: String, out: java.io.File): java.io.File {
        return withContext(Dispatchers.Default) { api?.downloadFileTo(path, out) ?: out }
    }
    /** Upload a local file (base64) into the given workspace directory, then refresh. */
    fun uploadFile(dir: String, name: String, base64: String, onDone: (String?) -> Unit = {}) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.Default) { api?.uploadFile(dir, name, base64) }
                listDir(dir)
                toast(Strings.str("uploaded_fmt", name))
                onDone(null)
            } catch (e: Exception) {
                toast(Strings.str("upload_failed_fmt", e.message))
                onDone(e.message)
            }
        }
    }
    /** Delete a file/directory in the workspace, then refresh the current listing. */
    fun deleteFile(path: String, onDone: (String?) -> Unit = {}) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.Default) { api?.deleteFile(path) }
                listDir(_dirPath.value)
                toast(Strings.str("deleted_file"))
                onDone(null)
            } catch (e: Exception) {
                toast(Strings.str("delete_failed_fmt", e.message))
                onDone(e.message)
            }
        }
    }
    /** Rename a file/directory in the workspace, then refresh. */
    fun renameFile(path: String, name: String, onDone: (String?) -> Unit = {}) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.Default) { api?.renameFile(path, name) }
                listDir(_dirPath.value)
                toast(Strings.str("renamed"))
                onDone(null)
            } catch (e: Exception) {
                toast(Strings.str("rename_failed_fmt", e.message))
                onDone(e.message)
            }
        }
    }
    /** Duplicate a file in the workspace, then refresh. */
    fun copyFile(path: String, onDone: (String?) -> Unit = {}) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.Default) { api?.copyFile(path) }
                listDir(_dirPath.value)
                toast(Strings.str("copied_file"))
                onDone(null)
            } catch (e: Exception) {
                toast(Strings.str("copy_failed_fmt", e.message))
                onDone(e.message)
            }
        }
    }
    /** Create a folder in the current directory, then refresh. */
    fun mkdir(name: String, onDone: (String?) -> Unit = {}) {
        val dir = _dirPath.value
        if (dir.isNullOrBlank()) {
            // At the drive-selector there is no directory to create inside yet.
            val m = Strings.str("mkdir_need_dir")
            toast(m)
            onDone(m)
            return
        }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.Default) { api?.mkdirFile(dir, name) }
                listDir(dir)
                toast(Strings.str("mkdir_ok_fmt", name))
                onDone(null)
            } catch (e: Exception) {
                toast(Strings.str("mkdir_failed_fmt", e.message))
                onDone(e.message)
            }
        }
    }
    // --- Subagents ---
    fun loadSubagents() {
        val sessionId = _currentSessionId.value ?: return
        viewModelScope.launch {
            try {
                _subagents.value = withContext(Dispatchers.Default) { api?.subagentList(sessionId) }?.entries ?: emptyList()
            } catch (_: Exception) {}
        }
    }
    fun openSubagent(entry: SubagentEntry) {
        val parent = _currentSessionId.value ?: return
        val mode = entry.mode ?: "one-shot"
        _subagentId.value = entry.id
        viewModelScope.launch {
            try {
                val history = withContext(Dispatchers.Default) { api?.subagentHistory(parent, entry.id, mode) }
                if (history != null) {
                    _subagentChat.value = foldChat(history.events.map { it.event })
                } else {
                    _subagentChat.value = emptyList()
                }
            } catch (_: Exception) {
                _subagentChat.value = emptyList()
            }
        }
    }
    /** Open a subagent child directly from the sidebar tree (parent + child session ids). */
    fun openSubagentSession(parentSessionId: String, childSessionId: String) {
        _currentSessionId.value = parentSessionId
        _subagentId.value = childSessionId
        viewModelScope.launch {
            try {
                val catalog = withContext(Dispatchers.Default) { api?.subagentList(parentSessionId) }
                _subagents.value = catalog?.entries ?: emptyList()
                val entry = _subagents.value.firstOrNull { it.id == childSessionId }
                val mode = entry?.mode ?: "one-shot"
                val history = withContext(Dispatchers.Default) { api?.subagentHistory(parentSessionId, childSessionId, mode) }
                _subagentChat.value = history?.let { foldChat(it.events.map { e -> e.event }) } ?: emptyList()
            } catch (_: Exception) {
                _subagentChat.value = emptyList()
            }
        }
    }
    fun interruptSubagent(entry: SubagentEntry) {
        val parent = _currentSessionId.value ?: return
        if (entry.mode != "continuable") return
        viewModelScope.launch {
            try { api?.subagentInterrupt(parent, entry.id); loadSubagents() } catch (_: Exception) {}
        }
    }
    fun subagentBack() {
        _subagentId.value = null
        _subagentChat.value = emptyList()
    }
    // --- Agent presets / reasoning effort ---
    fun loadAgentPresets() {
        viewModelScope.launch {
            try {
                _agentPresets.value = withContext(Dispatchers.Default) { api?.agentPresetList() }?.presets ?: emptyList()
            } catch (_: Exception) {}
        }
    }
    fun selectAgentPreset(id: String) {
        val sessionId = _currentSessionId.value ?: return
        viewModelScope.launch {
            try { api?.agentPresetSelect(sessionId, id) } catch (e: Exception) { _error.value = e.message }
        }
    }
    fun selectReasoningEffort(effortId: String) {
        val sessionId = _currentSessionId.value ?: return
        val current = _models.value?.current ?: return
        viewModelScope.launch {
            try {
                api?.sessionSelectModel(sessionId, current.provider, current.model, effortId)
                refreshModels()
            } catch (_: Exception) {}
        }
    }
    // --- Full-text session search ---
    fun searchSessions(query: String, onResult: (List<Pair<String, String>>) -> Unit) {
        val q = query.trim()
        if (q.isEmpty()) { onResult(emptyList()); return }
        viewModelScope.launch {
            try {
                val v = withContext(Dispatchers.Default) { api?.sessionSearch(q) }
                onResult(v?.items?.map { it.sessionId to it.snippet } ?: emptyList())
            } catch (_: Exception) { onResult(emptyList()) }
        }
    }
    // --- Workspace management ---
    fun createWorkspace(path: String) {
        viewModelScope.launch {
            try { api?.workspaceCreate(path); refreshAll() } catch (e: Exception) { _error.value = e.message }
        }
    }
    fun renameWorkspace(workspaceId: String, title: String) {
        viewModelScope.launch {
            try { api?.workspaceRename(workspaceId, title); refreshAll() } catch (_: Exception) {}
        }
    }
    fun deleteWorkspace(workspaceId: String) {
        viewModelScope.launch {
            try { api?.workspaceDelete(workspaceId); refreshAll() } catch (e: Exception) { _error.value = e.message }
        }
    }
    override fun onCleared() {
        // Flush any pending debounced draft write before the ViewModel is destroyed.
        val snapshot = draftBySession.toMap()
        try {
            runBlocking { settings.saveDrafts(snapshot) }
        } catch (_: Exception) {
        }
        super.onCleared()
    }
}
