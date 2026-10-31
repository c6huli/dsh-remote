package dev.dsh.remote.ui

import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.dsh.remote.data.ModelGroup
import dev.dsh.remote.data.SettingsDescribeValue
import dev.dsh.remote.data.SettingsNamespace
import dev.dsh.remote.ui.icons.DshIcon
import dev.dsh.remote.ui.icons.DshIcons
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** One editable setting row. */
private data class SettingItem(
    val ns: String,
    val key: String,
    val label: String,
    val desc: String = "",
    val options: List<String>? = null,
    val bool: Boolean = false,
)

private val CURATED_KEYS = setOf(
    "permission.defaultPreset",
    "ui-chat.performanceUsage",
    "ui-chat.linkOpening",
    "ui-conversation.busyEnter",
    "session-log-deepseek.enabled",
    "agent-default-model.provider",
    "agent-default-model.model",
    "agent-default-model.reasoningEffort",
    "ui-theme.preference",
    "ui-theme.fontSize",
)

private val REASONING_OPTIONS = listOf("off", "minimal", "low", "medium", "high")

/** Friendly titles for the namespaces that own plugin-ish settings. */
private val NS_TITLES = mapOf(
    "better-sidebar" to "侧边卡片",
    "dsh-ops-shell" to "shell 工具",
    "dsh-ops-file" to "文件工具",
    "dsh-ops-ptc" to "PTC 工具",
    "dsh-ops-discovery" to "工具发现",
    "dsh-market" to "插件市场",
    "plugin-manager" to "插件安装",
    "agent-preset-registry" to "Agent 预设",
    "subagent" to "子代理",
    "subagent-model-selection-settings" to "子代理模型",
    "web-search-deepseek" to "网页搜索",
    "permission" to "权限",
    "channel-pack" to "渠道",
    "pwsh-sandbox" to "pwsh 沙箱",
    "agent-loop" to "智能体循环",
    "ui-settings-general" to "界面通用",
    "ui-settings-account" to "账号界面",
    "ui-chat" to "会话显示",
    "ui-conversation" to "发送行为",
    "locale" to "语言与地区",
    "opencode2dsh" to "模型探测",
    "llm-buddy" to "llm-buddy",
)

private fun primitiveText(p: JsonPrimitive): String = if (p.isString) p.content else p.toString()

private fun coerceLike(old: JsonObject?, key: String, text: String): JsonPrimitive {
    val oldVal = old?.get(key)
    return when {
        oldVal is JsonPrimitive && !oldVal.isString -> {
            val asLong = text.toLongOrNull()
            val asDouble = text.toDoubleOrNull()
            val asBool = text.toBooleanStrictOrNull()
            when {
                asLong != null -> JsonPrimitive(asLong)
                asDouble != null -> JsonPrimitive(asDouble)
                asBool != null -> JsonPrimitive(asBool)
                else -> JsonPrimitive(text)
            }
        }
        else -> JsonPrimitive(text)
    }
}

/**
 * Web Settings: 通用 / 模型 / 更多.
 *
 * 模型 lists every provider the host advertises and lets its config be edited
 * or removed; 更多 replaces the old catch-all "其他" with one entry per
 * namespace, each opening its own page.
 */
@Composable
fun WebSettingsScreen(vm: AppViewModel, onBack: () -> Unit, onOpenDrawer: () -> Unit = {}) {
    val connected by vm.connected.collectAsState()
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<SettingsDescribeValue?>(null) }
    var modelGroups by remember { mutableStateOf<List<ModelGroup>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf(0) }
    var openNs by remember { mutableStateOf<String?>(null) }
    var editProvider by remember { mutableStateOf<Pair<String, String>?>(null) } // ns to providerId
    var editText by remember { mutableStateOf("") }
    // Opening settings goes straight to the host's own settings page: the native
    // tabs below are only a fallback when the WebView cannot be used at all.
    var showWebChrome by remember { mutableStateOf(true) }
    var webReady by remember { mutableStateOf(false) }

    val serverUrl by vm.serverUrl.collectAsState()
    val activeBaseUrl by vm.activeBaseUrl.collectAsState()

    LaunchedEffect(showWebChrome) {
        if (showWebChrome) {
            // Theme sync must never block the page: cap it and continue anyway.
            kotlinx.coroutines.withTimeoutOrNull(3_000L) { vm.syncWebTheme() }
            // Load the host page directly. It used to go through the in-app proxy on
            // 127.0.0.1:3086, which asks the host for *uncompressed* bytes — the
            // client bundle is several MB gzipped, so over a relayed tailnet link
            // that hop never finished and the panel sat on "加载超时（60 秒）".
            // The panel-only view is applied by injected JS instead.
            webReady = true
        } else {
            webReady = false
        }
    }

    suspend fun refresh() {
        busy = true
        msg = null
        try {
            data = vm.loadWebSettings()
            modelGroups = vm.loadModelCatalog()
        } catch (e: Exception) {
            msg = "${e.javaClass.simpleName}: ${e.message ?: "no message"}"
        } finally {
            busy = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    fun nsOf(ns: String): SettingsNamespace? = data?.namespaces?.firstOrNull { it.ns == ns }

    fun currentValue(ns: String, key: String): String? {
        val obj = nsOf(ns)?.value as? JsonObject ?: return null
        return (obj[key] as? JsonPrimitive)?.let { primitiveText(it) }
    }

    fun save(ns: String, key: String, newValue: String) {
        scope.launch {
            val n = nsOf(ns)
            if (n == null) {
                msg = "找不到设置分组 $ns"
                return@launch
            }
            busy = true
            msg = null
            try {
                val patch = buildJsonObject {
                    put(key, coerceLike(n.value as? JsonObject, key, newValue))
                }
                vm.updateWebSetting(n.ns, patch, n.revision)
                msg = "已保存"
                refresh()
            } catch (e: Exception) {
                msg = "${e.javaClass.simpleName}: ${e.message ?: "no message"}"
                busy = false
            }
        }
    }

    /** Write a provider's whole config object back into its llm-* namespace. */
    fun saveProvider(ns: String, providerId: String, jsonText: String) {
        scope.launch {
            val n = nsOf(ns)
            if (n == null) {
                msg = "找不到 $ns"
                return@launch
            }
            busy = true
            msg = null
            try {
                val parsed = kotlinx.serialization.json.Json.parseToJsonElement(jsonText) as? JsonObject
                    ?: throw IllegalArgumentException("JSON 必须是对象")
                val providers = (n.value as? JsonObject)?.get("providers") as? JsonObject ?: JsonObject(emptyMap())
                val patch = buildJsonObject {
                    put("providers", JsonObject(providers + (providerId to parsed)))
                }
                vm.updateWebSetting(ns, patch, n.revision)
                msg = "已保存 $providerId"
                editProvider = null
                refresh()
            } catch (e: Exception) {
                msg = "${e.javaClass.simpleName}: ${e.message ?: "no message"}"
                busy = false
            }
        }
    }

    // ---- provider list: catalog providers + their owning llm-* namespace ----
    val providers = remember(data, modelGroups) {
        val byId = mutableMapOf<String, Triple<String, Int, String>>() // id -> (ns, models, rawJson)
        data?.namespaces?.filter { it.ns.startsWith("llm-") }?.forEach { n ->
            val provs = (n.value as? JsonObject)?.get("providers") as? JsonObject ?: return@forEach
            provs.entries.forEach { (id, cfg) ->
                val models = (cfg as? JsonObject)?.get("models")?.jsonArray?.size ?: 0
                byId[id] = Triple(n.ns, models, cfg.toString())
            }
        }
        val fromCatalog = modelGroups.map { g ->
            val hit = byId[g.id]
            Triple(g.id, hit?.second ?: g.models.size, hit?.first ?: "llm-pi-ai")
        }
        val extra = byId.keys.filter { id -> modelGroups.none { it.id == id } }
            .map { id -> val h = byId.getValue(id); Triple(id, h.second, h.first) }
        (fromCatalog + extra).sortedBy { it.first }
    }

    // ---- group tabs ----
    val generalItems = listOf(
        SettingItem("permission", "defaultPreset", "权限", "选择新会话的默认权限模式",
            listOf("danger-full-access", "ask", "never")),
        SettingItem("ui-chat", "performanceUsage", "工作步骤展示", "选择希望看到多少工具调用细节",
            listOf("detailed", "compact", "off")),
        SettingItem("ui-conversation", "busyEnter", "繁忙时的发送行为", "智能体运行时 Enter 键和发送按钮的行为",
            listOf("queue", "steer", "interrupt")),
        SettingItem("ui-chat", "linkOpening", "链接打开方式", "点击对话中的链接时如何打开",
            listOf("sidebar", "browser")),
        SettingItem("session-log-deepseek", "enabled", "记录会话日志", "开启后会把会话轨迹写入本地日志",
            bool = true),
    )

    val otherNamespaces = remember(data) {
        data?.namespaces?.filter { n ->
            n.ns !in setOf("permission", "ui-chat", "ui-conversation", "session-log-deepseek",
                "agent-default-model", "ui-theme") && !n.ns.startsWith("llm-")
        }?.filter { n ->
            val obj = n.value as? JsonObject ?: return@filter false
            obj.entries.any { (k, v) -> v is JsonPrimitive && "${n.ns}.$k" !in CURATED_KEYS }
        } ?: emptyList()
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        if (showWebChrome) {
            // Align the host theme first, then load: the page picks up ui-theme on
            // boot, so syncing after load would show the previous mode.
            if (!webReady) {
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "正在加载 Web 设置…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val base = (activeBaseUrl ?: serverUrl).ifBlank { "http://127.0.0.1:8080" }.trimEnd('/')
                // Straight to the host: the localhost proxy hop only added a failure
                // mode, because it disabled compression on a multi-MB bundle.
                WebChromePanel(
                    url = "$base/",
                    injectSettings = true,
                    onOpenDrawer = onOpenDrawer,
                    // Back from the host's settings page leaves settings entirely:
                    // it is the settings surface, not a page inside another one.
                    onClose = { onBack() },
                )
            }
            return@Column
        }

        // ---- header ----
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { if (openNs != null) openNs = null else onBack() }) {
                DshIcon(DshIcons.ChevronLeft, tint = MaterialTheme.colorScheme.onSurface, size = 22.dp, contentDescription = "返回")
            }
            IconButton(onClick = onOpenDrawer) {
                DshIcon(DshIcons.PanelLeft, tint = MaterialTheme.colorScheme.onSurface, size = 20.dp, contentDescription = "侧边栏")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    openNs?.let { NS_TITLES[it] ?: it } ?: "设置",
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (connected) "已连接" else "未连接",
                        color = if (connected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    if (busy) {
                        Spacer(Modifier.width(6.dp))
                        CircularProgressIndicator(modifier = Modifier.size(10.dp), strokeWidth = 1.5.dp)
                    }
                }
            }
            if (!connected) TextButton(onClick = { vm.connect() }, enabled = !busy) { Text("重连") }
            TextButton(onClick = { scope.launch { refresh() } }, enabled = !busy) { Text("刷新") }
        }

        msg?.let {
            Text(
                it,
                color = if (it.startsWith("已保存")) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 14.dp),
            )
        }

        // ---- tab bar (hidden inside a namespace page) ----
        if (openNs == null) {
            val names = listOf("通用", "模型", "更多")
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                names.forEachIndexed { index, name ->
                    val selected = tab == index
                    Text(
                        name,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                RoundedCornerShape(14.dp),
                            )
                            .clickable { tab = index }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        }

        val current = data
        if (current == null) {
            Spacer(Modifier.height(16.dp))
            Text(
                if (busy) "正在加载…" else "未加载到设置",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 14.dp),
            )
            return@Column
        }

        // ---- namespace detail page ----
        val nsPage = openNs
        if (nsPage != null) {
            val n = nsOf(nsPage)
            val obj = n?.value as? JsonObject
            LazyColumn(Modifier.fillMaxSize()) {
                if (obj == null || obj.isEmpty()) {
                    item { Text("(空)", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(14.dp)) }
                } else {
                    items(obj.entries.filter { it.value is JsonPrimitive }.toList(), key = { it.key }) { (k, v) ->
                        val value = primitiveText(v as JsonPrimitive)
                        SettingRow(
                            item = SettingItem(nsPage, k, k),
                            value = value,
                            onPick = { save(nsPage, k, it) },
                            onEdit = {
                                editProvider = nsPage to k
                                editText = value
                            },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    }
                }
            }
            return@Column
        }

        when (tab) {
            0 -> LazyColumn(Modifier.fillMaxSize()) {
                items(generalItems, key = { "${it.ns}.${it.key}" }) { item ->
                    val value = currentValue(item.ns, item.key)
                    if (value != null) {
                        SettingRow(item, value, onPick = { save(item.ns, item.key, it) }, onEdit = {
                            editProvider = item.ns to item.key
                            editText = value
                        })
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    }
                }
            }

            1 -> LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Text(
                        "填入各提供商的 API 密钥即可使用其模型。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
                items(providers, key = { it.first }) { (id, count, ns) ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(7.dp)
                                .background(
                                    if (count > 0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline,
                                    CircleShape,
                                ),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(id, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "$count 个模型 · $ns",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                val cfg = (nsOf(ns)?.value as? JsonObject)
                                    ?.get("providers")?.jsonObject
                                    ?.get(id)?.toString() ?: "{}"
                                editProvider = ns to id
                                editText = cfg
                            },
                            modifier = Modifier.height(32.dp),
                        ) { Text("编辑", style = MaterialTheme.typography.labelMedium) }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                }
                item {
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            editProvider = "llm-pi-ai" to "__new__"
                            editText = "{\n  \"apiKeyEnv\": \"MY_API_KEY\",\n  \"models\": []\n}"
                        },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                    ) { Text("+ 添加模型提供商") }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "新增后需在 Web 端补齐密钥与模型列表。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 14.dp),
                    )
                }
            }

            else -> LazyColumn(Modifier.fillMaxSize()) {
                item {
                    // The host renders plugin settings itself; embed it instead of
                    // approximating those screens with a few fields.
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { showWebChrome = true }
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("打开完整 Web 设置", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "含插件、Agent 预设、插件市场等全部界面",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        DshIcon(DshIcons.ChevronDown, tint = MaterialTheme.colorScheme.primary, size = 16.dp)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                }
                item {
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                }
                items(otherNamespaces, key = { it.ns }) { n ->
                    val count = (n.value as? JsonObject)?.entries?.count { it.value is JsonPrimitive } ?: 0
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { openNs = n.ns }
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(NS_TITLES[n.ns] ?: n.ns, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                n.ns,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "$count 项",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(4.dp))
                        DshIcon(DshIcons.ChevronDown, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 14.dp)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                }
            }
        }
    }

    // ---- JSON editor (provider config or a single free-form value) ----
    editProvider?.let { (ns, key) ->
        val isProvider = key != "__single" && (ns.startsWith("llm-") || key == "__new__")
        val title = when {
            key == "__new__" -> "新增提供商标识（JSON 为配置，名称取 id 行）"
            ns.startsWith("llm-") && !key.any { it == '.' } -> "编辑 $key"
            else -> "编辑 $key"
        }
        AlertDialog(
            onDismissRequest = { editProvider = null },
            title = { Text(title, style = MaterialTheme.typography.titleSmall) },
            text = {
                Column {
                    if (key == "__new__") {
                        Text(
                            "把配置里的 id 写进 providers 键；页面保存时会用 __new__ 作为占位，请在 Web 端改名。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    OutlinedTextField(
                        value = editText,
                        onValueChange = { editText = it },
                        modifier = Modifier.fillMaxWidth().height(220.dp),
                        textStyle = MaterialTheme.typography.bodySmall,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val text = editText
                    val provId = if (key == "__new__") "__new__" else key
                    if (isProvider) {
                        saveProvider(ns, provId, text)
                    } else {
                        editProvider = null
                        save(ns, key, text.trim())
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editProvider = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun SettingRow(
    item: SettingItem,
    value: String,
    onPick: (String) -> Unit,
    onEdit: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.label, style = MaterialTheme.typography.bodyMedium)
            if (item.desc.isNotBlank()) {
                Text(
                    item.desc,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        when {
            item.bool -> Switch(checked = value == "true", onCheckedChange = { onPick(it.toString()) })
            item.options != null -> PickerChip(value, item.options) { onPick(it) }
            else -> PickerChip(value, null) { onEdit() }
        }
    }
}

@Composable
private fun PickerChip(value: String, options: List<String>?, onClick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                .clickable { if (options != null) open = true else onClick("") }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                value.ifBlank { "未设置" },
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.width(4.dp))
            DshIcon(DshIcons.ChevronDown, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 14.dp)
        }
        if (options != null) {
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (o in options) {
                    DropdownMenuItem(text = { Text(o) }, onClick = { open = false; onClick(o) })
                }
            }
        }
    }
}

/**
 * Clicks the host's Settings entry as soon as it exists.
 *
 * The Web app is a single page with no /settings route, so the panel has to be
 * opened by clicking its rail button. This retries briefly because the SPA
 * mounts its chrome asynchronously after the load event.
 */
private const val OPEN_SETTINGS_JS = """
(function () {
  function open() {
    var b = document.querySelector('button[aria-label="设置"]');
    if (b) { b.click(); return true; }
    return false;
  }
  if (open()) return;
  var tries = 0;
  var t = setInterval(function () {
    if (open() || ++tries > 60) { clearInterval(t); }
  }, 250);
})();
"""

/**
 * Receives 「设置面板的 ✕ 被点了」 from the injected page script.
 *
 * Closing the dialog inside the host's page would otherwise leave this WebView on
 * the bare app — a blank screen — so the ✕ is routed back to the app's own exit.
 */
private class PanelJsBridge(private val onClose: () -> Unit) {
    @android.webkit.JavascriptInterface
    fun closeSettings() {
        // Called on a WebView JS thread; Compose state must change on the main one.
        android.os.Handler(android.os.Looper.getMainLooper()).post { onClose() }
    }
}

/**
 * The host's own Web UI in a WebView.
 *
 * Plugin settings (插件安装 / 插件市场 / Agent 预设 / Remotion / Mobile Remote …)
 * are React components the host renders; re-implementing them natively would
 * always lag behind. Embedding the real page keeps every plugin screen working
 * and mirrors the desktop experience exactly.
 */
@Composable
private fun WebChromePanel(
    url: String,
    onClose: () -> Unit,
    injectSettings: Boolean = true,
    onOpenDrawer: () -> Unit = {},
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageTitle by remember { mutableStateOf("Web 设置") }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(0) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val bgColor = MaterialTheme.colorScheme.background
    val bgArgb = android.graphics.Color.argb(
        (bgColor.alpha * 255).toInt(),
        (bgColor.red * 255).toInt(),
        (bgColor.green * 255).toInt(),
        (bgColor.blue * 255).toInt(),
    )

    // WebView has no built-in timeout; a stalled request would show a blank page
    // forever, so report it.
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(60_000L)
        if (loading && loadError == null) {
            loadError = "加载超时（60 秒），检查手机能否访问该地址"
            loading = false
        }
    }

    BackHandler {
        val w = webView
        if (w != null && w.canGoBack()) w.goBack() else onClose()
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
                val w = webView
                if (w != null && w.canGoBack()) w.goBack() else onClose()
            }) {
                DshIcon(DshIcons.ChevronLeft, tint = MaterialTheme.colorScheme.onSurface, size = 22.dp, contentDescription = "返回")
            }
            // The WebView swallows edge swipes, so the drawer needs an explicit entry.
            IconButton(onClick = onOpenDrawer) {
                DshIcon(DshIcons.PanelLeft, tint = MaterialTheme.colorScheme.onSurface, size = 20.dp, contentDescription = "侧边栏")
            }
            Column(Modifier.weight(1f)) {
                Text(pageTitle, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                if (loading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(10.dp), strokeWidth = 1.5.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (progress in 1..99) "加载中 $progress%" else "加载中…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            TextButton(onClick = { webView?.reload() }) { Text("刷新") }
            // Re-open the settings sheet if the user navigated away from it.
            TextButton(onClick = {
                try { webView?.evaluateJavascript(OPEN_SETTINGS_JS, null) } catch (_: Throwable) {}
            }) { Text("设置") }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        loadError?.let {
            // Surface the failure instead of showing a blank page forever.
            Text(
                "加载失败 $it\n地址 $url",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        AndroidView(
            factory = { ctx ->
                // The embedded WebView is optional; a trimmed device (or a limited
                // VM) must degrade to a message instead of crashing the app.
                try {
                    android.webkit.WebView(ctx).apply {
                        // Match the page background so the first paint does not flash white.
                        setBackgroundColor(bgArgb)
                        with(settings) {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            // Lay the page out at device width. A wide layout viewport
                            // shrank the whole panel to unreadable size and left most
                            // of the screen empty; the injected styles stretch the
                            // settings dialog to fill the viewport instead.
                            useWideViewPort = false
                            loadWithOverviewMode = false
                            setSupportZoom(false)
                            builtInZoomControls = false
                            displayZoomControls = false
                            textZoom = 100
                            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: android.webkit.WebView?, u: String?) {
                                loading = false
                                progress = 100
                                // The phone-fit transform used to arrive through the
                                // local proxy's HTML rewrite; it is applied here now,
                                // so this panel no longer depends on a localhost hop.
                                try {
                                    view?.evaluateJavascript(dev.dsh.remote.net.WebProxyServer.PANEL_FIT_JS, null)
                                } catch (_: Throwable) {}
                                if (injectSettings) {
                                    // This panel only shows the settings surface, so open it.
                                    try { view?.evaluateJavascript(OPEN_SETTINGS_JS, null) } catch (_: Throwable) {}
                                }
                            }

                            override fun onReceivedError(
                                view: android.webkit.WebView?,
                                request: android.webkit.WebResourceRequest?,
                                error: android.webkit.WebResourceError?,
                            ) {
                                if (request?.isForMainFrame == true) {
                                    loadError = "${error?.errorCode}: ${error?.description}"
                                    loading = false
                                }
                            }

                            // The "flash then blank" symptom is this: the WebView
                            // renderer died and Android wants us to handle it.
                            override fun onRenderProcessGone(
                                view: android.webkit.WebView?,
                                detail: android.webkit.RenderProcessGoneDetail?,
                            ): Boolean {
                                loadError = if (detail?.didCrash() == true) {
                                    "WebView 渲染进程崩溃（虚拟机/内存限制）"
                                } else {
                                    "WebView 渲染进程被系统回收"
                                }
                                loading = false
                                return true
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onReceivedTitle(view: android.webkit.WebView?, title: String?) {
                                if (!title.isNullOrBlank()) pageTitle = title
                            }

                            override fun onProgressChanged(view: android.webkit.WebView?, newProgress: Int) {
                                progress = newProgress
                                // A page that streams in and stalls below 100 must not
                                // keep the spinner forever.
                                if (newProgress >= 100) loading = false
                            }
                        }
                        // Lets the injected panel script hand the dialog's ✕ back to
                        // the app instead of leaving a blank page behind.
                        try {
                            addJavascriptInterface(PanelJsBridge(onClose), "DshNative")
                        } catch (_: Throwable) {}
                        try { loadUrl(url) } catch (_: Throwable) {}
                        webView = this
                    }
                } catch (e: Throwable) {
                    android.widget.TextView(ctx).apply {
                        text = "系统 WebView 不可用（${e.javaClass.simpleName}）。请在系统设置里安装/更新 Android System WebView 后重试。"
                        setPadding(32, 48, 32, 48)
                        setTextColor(android.graphics.Color.GRAY)
                    }
                }
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            onRelease = { v -> try { if (v is WebView) v.destroy() } catch (_: Throwable) {} },
        )
    }
}