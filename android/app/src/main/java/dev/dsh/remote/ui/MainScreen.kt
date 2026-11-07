package dev.dsh.remote.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewSidebar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import dev.dsh.remote.ui.icons.DshIcon
import dev.dsh.remote.ui.icons.DshIcons
import dev.dsh.remote.ui.theme.AppShape
import dev.dsh.remote.ui.theme.LocalDshSemantic
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.dsh.remote.data.WorkspaceView
import dev.dsh.remote.data.SessionSummary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: AppViewModel) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val sessions by vm.sessions.collectAsState()
    val currentId by vm.currentSessionId.collectAsState()
    val connected by vm.connected.collectAsState()
    val connecting by vm.connecting.collectAsState()
    val error by vm.error.collectAsState()
    val running by vm.running.collectAsState()
    val title = sessions.firstOrNull { it.sessionId == currentId }?.title ?: "DSH Remote"
    // 连接失败提示条是否被用户手动关掉；重新连上后自动复位，下次再失败还会提示。
    var noticeDismissed by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(connected) { if (connected) noticeDismissed = false }
    var showPanel by rememberSaveable { mutableStateOf(false) }
    var showFileBrowser by remember { mutableStateOf(false) }
    var showSubagents by remember { mutableStateOf(false) }
    var pickWorkspaceDir by remember { mutableStateOf(false) }
    // Settings live inside the drawer's scope, so the sidebar stays reachable from
    // every screen instead of being replaced by a top-level navigation switch.
    var showSettings by remember { mutableStateOf(false) }
    // 手机操控页：侧边栏入口，受设置里的总开关控制。
    var showPhone by remember { mutableStateOf(false) }
    val wantDownloads by vm.openDownloads.collectAsState()
    androidx.compose.runtime.LaunchedEffect(wantDownloads) { if (wantDownloads) showSettings = true }
    // 文件变动 summary reported by the embedded panel; shown under the conversation.
    var fileChanges by remember { mutableStateOf<Pair<String, List<String>>?>(null) }
    // Never show another session's numbers: the panel reports whatever the host page
    // has open, so the cached summary is dropped on every session switch.
    val activeSessionId by vm.currentSessionId.collectAsState()
    androidx.compose.runtime.LaunchedEffect(activeSessionId) { fileChanges = null }
    val openDrawer: () -> Unit = { scope.launch { drawerState.open() } }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // Opened from the top-left button only: an edge swipe fights the chat's
        // horizontal gestures (and the embedded Web panels) far too often.
        gesturesEnabled = false,
        drawerContent = {
            // 抽屉自己画成玻璃面板:不透明底会把页面渐变挡死,只剩一块死白。
            val surfaces = dev.dsh.remote.ui.theme.LocalDshSurfaces.current
            Box(
                Modifier
                    .width(312.dp)
                    .fillMaxHeight()
                    .clip(
                        RoundedCornerShape(
                            topEnd = dev.dsh.remote.ui.theme.AppShape.xl,
                            bottomEnd = dev.dsh.remote.ui.theme.AppShape.xl,
                        ),
                    )
                    .background(surfaces.sheet.fallbackFill)
                    .drawBehind { }
            ) {
                SidebarContent(
                    vm = vm,
                    onCollapse = { scope.launch { drawerState.close() } },
                    onSelectSession = { id ->
                        vm.openSession(id)
                        scope.launch { drawerState.close() }
                    },
                    onSelectSubagent = { parentId, childId ->
                        vm.openSubagentSession(parentId, childId)
                        showSubagents = true
                        scope.launch { drawerState.close() }
                    },
                    onOpenSettings = {
                        showSettings = true
                        scope.launch { drawerState.close() }
                    },
                    onOpenFiles = {
                        vm.openFileBrowser()
                        showFileBrowser = true
                        scope.launch { drawerState.close() }
                    },
                    onOpenSubagents = {
                        showSubagents = true
                        scope.launch { drawerState.close() }
                    },
                    onOpenPhone = {
                        showPhone = true
                        scope.launch { drawerState.close() }
                    },
                    onCreateWorkspace = {
                        vm.openFileBrowser()
                        pickWorkspaceDir = true
                        scope.launch { drawerState.close() }
                    },
                )
            }
        },
    ) {
        if (showPhone) {
            androidx.activity.compose.BackHandler { showPhone = false }
            PhoneScreen(vm, onBack = { showPhone = false })
        } else if (showSettings) {
            // Inside the drawer, but instead of the app's own chrome: the settings
            // screen brings its own header.
            androidx.activity.compose.BackHandler { showSettings = false }
            SettingsScreen(
                vm,
                onBack = { showSettings = false },
                onOpenDrawer = openDrawer,
            )
        } else {
            // 顶栏不用 Material 的 TopAppBar:它带不透明底色和 elevation,会把
            // 页面渐变切出一道。这里自己排:玻璃圆钮 + 标题 + 玻璃圆钮,浮在内容上。
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        dev.dsh.remote.ui.theme.GlassIconButton(
                            icon = DshIcons.PanelLeft,
                            onClick = { scope.launch { drawerState.open() } },
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (running) {
                                    dev.dsh.remote.ui.theme.BreathingDot(
                                        color = MaterialTheme.colorScheme.primary,
                                        size = 7.dp,
                                        running = true,
                                    )
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(
                                    title,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                        dev.dsh.remote.ui.theme.GlassIconButton(
                            icon = DshIcons.Fish,
                            onClick = { vm.goHome() },
                        )
                        Spacer(Modifier.width(6.dp))
                        dev.dsh.remote.ui.theme.GlassIconButton(
                            icon = DshIcons.Data,
                            onClick = { showPanel = !showPanel },
                        )
                        Spacer(Modifier.width(6.dp))
                        dev.dsh.remote.ui.theme.GlassIconButton(
                            icon = DshIcons.Settings,
                            onClick = { showSettings = true },
                        )
                    }
                    Box(Modifier.fillMaxSize()) {
                when {
                    showFileBrowser -> FileBrowserScreen(vm, onBack = { showFileBrowser = false })
                    pickWorkspaceDir -> FileBrowserScreen(
                        vm,
                        onBack = { pickWorkspaceDir = false },
                        pickMode = true,
                        onPickDirectory = { dir ->
                            vm.createWorkspace(dir)
                            pickWorkspaceDir = false
                        },
                    )
                    showSubagents -> SubagentScreen(vm, onBack = { showSubagents = false })
                    else -> {
                        when {
                            currentId == null && connecting -> LoadingState(Strings.str("connecting"))
                            currentId == null -> HomeScreen(vm, onSelect = { vm.openSession(it) })
                            else -> SessionView(
                                vm,
                                showPanel,
                                onClosePanel = { showPanel = false },
                                onOpenPanel = { showPanel = true },
                                fileChanges = fileChanges,
                                onFileChanges = { s, f -> fileChanges = s to f },
                                onOpenFiles = {
                                    vm.openFileBrowser()
                                    showFileBrowser = true
                                },
                                onOpenSubagents = { showSubagents = true },
                                onOpenPhone = { showPhone = true },
                                onOpenSettings = { showSettings = true },
                            )
                        }
                    }
                }
                    }
                }
            }
        }
    }

    // 连不上不再是"墙"。
    //
    // 原来是 onDismissRequest = {} 的模态对话框：只要主机不可达，整个界面就被
    // 挡住，连「设置」都要先关掉弹窗才能点——等于必须先连上才能用 App。现在改成
    // 顶部一条可关掉/可重试的提示，App 本身照常可用（设置、手机操控、已下载文件
    // 都不依赖连接），连上后提示自己消失。
    if (!connected && error != null && !noticeDismissed) {
        ConnectionNotice(
            message = error ?: Strings.str("cannot_connect_server"),
            onRetry = { vm.connect() },
            onOpenSettings = { showSettings = true },
            onDismiss = { noticeDismissed = true },
        )
    }
}

/**
 * 非阻塞的连接失败提示条。
 *
 * 刻意不用 AlertDialog：连接是后台行为，不该拦住前台。这里只说清楚"没连上"，
 * 并给出重试/设置/关掉三个出口，关掉后仍能正常使用 App 的本地部分。
 */
@Composable
private fun ConnectionNotice(
    message: String,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sem = LocalDshSemantic.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(AppShape.md))
                .background(sem.dangerContainer)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(
                Strings.str("connection_failed"),
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium,
                color = sem.onDangerContainer,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                message,
                style = MaterialTheme.typography.labelSmall,
                color = sem.onDangerContainer,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onRetry) {
                    Text(Strings.str("retry"), color = sem.onDangerContainer, fontWeight = FontWeight.SemiBold)
                }
                TextButton(onClick = onOpenSettings) {
                    Text(Strings.str("settings"), color = sem.onDangerContainer)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Text(Strings.str("close"), color = sem.onDangerContainer)
                }
            }
        }
    }
}

@Composable
private fun SessionView(
    vm: AppViewModel,
    showPanel: Boolean,
    onClosePanel: () -> Unit,
    onOpenPanel: () -> Unit = {},
    fileChanges: Pair<String, List<String>>? = null,
    onFileChanges: (String, List<String>) -> Unit = { _, _ -> },
    onOpenFiles: () -> Unit = {},
    onOpenSubagents: () -> Unit = {},
    onOpenPhone: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(Strings.str("conversation")) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(Strings.str("trajectory")) })
        }
        when (tab) {
            0 -> Box(Modifier.fillMaxSize()) {
                ChatScreen(vm, Modifier.fillMaxSize(), onOpenFilesPanel = { onOpenPanel() }, fileChanges = fileChanges)
                if (showPanel) {
                    // Scrim: dim the conversation so the panel visibly floats on
                    // top (the same overlay behaviour as the left drawer), and
                    // tapping outside the panel dismisses it.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.35f))
                            .clickable(onClick = onClosePanel),
                    )
                    RightPanel(
                        vm,
                        Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                        onClose = onClosePanel,
                        onOpenFiles = onOpenFiles,
                        onOpenSubagents = onOpenSubagents,
                        onOpenSettings = onOpenSettings,
                        onChanges = onFileChanges,
                    )
                }
            }
            else -> TrajectoryScreen(vm)
        }
    }
}

@Composable
private fun HomeScreen(vm: AppViewModel, onSelect: (String) -> Unit) {
    val sessions by vm.sessions.collectAsState()
    val finishedAt by vm.finishedSessions.collectAsState()
    val pendingQuestions by vm.pendingQuestions.collectAsState()
    val pendingApprovals by vm.pendingApprovals.collectAsState()

    // Blank host-side sessions are noise here as well.
    val archivedIds = vm.archivedSessionIds.collectAsState().value
    // 首页也只列有内容的会话:空壳(建了没说话)和归档的不占位置。
    val real = remember(sessions, archivedIds) {
        sessions.filter { it.sessionId !in archivedIds && !it.blank }
    }
    val runningSessions = remember(real) { real.filter { it.running && !it.isSubagent } }
    // Recently finished (≤5 min) and not yet opened.
    val finishedSessions = remember(real, finishedAt) {
        val now = System.currentTimeMillis()
        real.filter { s ->
            val t = finishedAt[s.sessionId] ?: return@filter false
            t >= now - 5 * 60 * 1000L
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item(key = "balance") { BalanceSummaryCard(vm) }

        if (pendingQuestions.isNotEmpty() || pendingApprovals.isNotEmpty()) {
            item(key = "h-decision") { SectionHeader(Strings.str("decisions")) }
            items(pendingQuestions, key = { "q-${it.rpcId}" }) { q ->
                val isPlan = q.questions.firstOrNull()?.intent?.kind == "plan-review"
                NotificationCard(
                    if (isPlan) DshIcons.Goal else DshIcons.Question,
                    if (isPlan) Strings.str("plan_review") else Strings.str("ai_asks"),
                    Strings.str("click_to_answer"),
                    unread = true,
                ) { onSelect(q.sessionId) }
            }
            items(pendingApprovals, key = { "a-${it.rpcId}" }) { a ->
                NotificationCard(DshIcons.Warning, "${Strings.str("needs_approval")} · ${a.toolName}", Strings.str("click_to_handle"), unread = true) { onSelect(a.sessionId) }
            }
        }

        if (runningSessions.isNotEmpty()) {
            item(key = "h-running") { SectionHeader(Strings.str("running")) }
            items(runningSessions, key = { "r-${it.sessionId}" }) { s ->
                NotificationCard(DshIcons.Play, s.title, Strings.str("running_dot_desc") + " · " + Strings.str("click_to_view")) { onSelect(s.sessionId) }
            }
        }

        if (finishedSessions.isNotEmpty()) {
            item(key = "h-finished") { SectionHeader(Strings.str("finished_recent")) }
            items(finishedSessions, key = { "f-${it.sessionId}" }) { s ->
                NotificationCard(DshIcons.Check, s.title, Strings.str("click_to_view"), unread = true) { onSelect(s.sessionId) }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    val sem = dev.dsh.remote.ui.theme.LocalDshSemantic.current
    Text(
        title,
        color = sem.textTertiary,
        style = dev.dsh.remote.ui.theme.AppType.eyebrow(),
        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp),
    )
}

@Composable
private fun BalanceSummaryCard(vm: AppViewModel) {
    val balance by vm.deepseekBalance.collectAsState()
    val apiKey by vm.deepseekApiKey.collectAsState()
    val info = balance?.balance_infos?.firstOrNull()

    val scheme = MaterialTheme.colorScheme
    val sem = dev.dsh.remote.ui.theme.LocalDshSemantic.current
    dev.dsh.remote.ui.theme.GlassSurface(
        tier = dev.dsh.remote.ui.theme.GlassTier.Card,
        shape = RoundedCornerShape(dev.dsh.remote.ui.theme.AppShape.lg),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                dev.dsh.remote.ui.theme.IconBadge(icon = DshIcons.Data, tint = scheme.primary, size = 26.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    Strings.str("balance_title"),
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleSmall,
                    color = scheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                dev.dsh.remote.ui.theme.GlassPill(
                    text = Strings.str("refresh"),
                    onClick = { vm.queryDeepseekBalance() },
                )
            }
            Spacer(Modifier.height(10.dp))
            when {
                info != null -> {
                    Text(
                        "${info.total_balance} ${info.currency}",
                        color = scheme.onSurface,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Light,
                    )
                    Text(
                        Strings.str(
                            "available",
                            if ((info.total_balance.toDoubleOrNull() ?: 0.0) > 0.0) Strings.str("yes") else Strings.str("no"),
                        ),
                        color = sem.success,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                apiKey.isBlank() -> Text(
                    Strings.str("api_key_not_set"),
                    color = sem.textTertiary,
                    style = MaterialTheme.typography.bodySmall,
                )
                else -> Text(
                    Strings.str("tap_refresh"),
                    color = sem.textTertiary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun NotificationCard(icon: Int, title: String, subtitle: String, unread: Boolean = false, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val sem = dev.dsh.remote.ui.theme.LocalDshSemantic.current
    dev.dsh.remote.ui.theme.GlassSurface(
        tier = dev.dsh.remote.ui.theme.GlassTier.Card,
        shape = RoundedCornerShape(dev.dsh.remote.ui.theme.AppShape.lg),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        onClick = onClick,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                dev.dsh.remote.ui.theme.IconBadge(
                    icon = icon,
                    tint = if (unread) scheme.primary else scheme.onSurfaceVariant,
                    size = 26.dp,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (unread) {
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.size(7.dp).background(scheme.tertiary, CircleShape))
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                subtitle,
                color = sem.textTertiary,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 36.dp),
            )
        }
    }
}

@Composable
private fun LoadingState(label: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SidebarContent(
    vm: AppViewModel,
    /** 收起侧边栏：抽屉展开后左上角那个侧边栏按钮就是它。 */
    onCollapse: () -> Unit,
    onSelectSession: (String) -> Unit,
    onSelectSubagent: (String, String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenSubagents: () -> Unit,
    onCreateWorkspace: () -> Unit,
    onOpenPhone: () -> Unit = {},
) {
    val workspaces by vm.workspaces.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val archivedIds by vm.archivedSessionIds.collectAsState()
    val currentId by vm.currentSessionId.collectAsState()
    // `blank` marks a session the host created but nobody has written to yet — those
    // showed up as mystery "新建会话 · 98e8" rows after an update. A session is kept
    // when it is the one open right now, so creating one is still visible.
    // 空会话(宿主刚建、还没人写过)也要显示。
    //
    // 早先这里按 `blank` 过滤,理由是"更新后会冒出莫名其妙的新建会话行"。但那让
    // **Web 端刚建的会话在手机上完全消失** —— 建完切到手机,列表里找不到。
    // 现在标题已有"新建会话 · 98e8"这样的占位名,不再像未知条目,所以照常显示;
    // 归档的仍然隐藏。
    // 列表只显示**有内容的、未归档的**会话。
    //
    // - `blank` = 宿主建了但还没有任何用户消息。刚点「新建会话」就冒出一行是噪音,
    //   而且这类空壳会在宿主侧越积越多(我这边一次看到 40 多个)。
    // - 归档的照旧隐藏。
    // - 当前打开的那条始终保留:否则新建完点进去,它自己反而从列表里消失。
    val activeSessions = sessions.filter {
        it.sessionId !in archivedIds && (!it.blank || it.sessionId == currentId)
    }
    val subagentCatalogs by vm.subagentChildrenByParent.collectAsState()
    var renameTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var sessionMenuTarget by remember { mutableStateOf<Pair<String, String>?>(null) } // sessionId to title
    var searchQuery by remember { mutableStateOf("") }
    var workspaceTarget by remember { mutableStateOf<WorkspaceView?>(null) }
    var workspaceRename by remember { mutableStateOf<Pair<String, String>?>(null) }
    // Creating a session needs a confirmation: a stray tap in the drawer used to
    // create one silently, which showed up as a mysterious new conversation.
    var newPrompt by remember { mutableStateOf(false) }
    var newWs by remember { mutableStateOf<String?>(null) }
    var expandedParents by remember { mutableStateOf(setOf<String>()) }

    // Main conversations (exclude subagent children).
    val mainSessions = remember(activeSessions) { activeSessions.filter { !it.isSubagent } }
    // Subagent children per parent: keep running one-shots and every continuable
    // (resumable) child; drop finished one-shot children. Catalog is primary,
    // session.list running children are a fallback while the catalog is loading.
    val childrenByParent = remember(activeSessions, subagentCatalogs) {
        val result = mutableMapOf<String, MutableList<SubagentChildRowView>>()
        fun add(parentId: String, view: SubagentChildRowView) {
            val list = result.getOrPut(parentId) { mutableListOf() }
            if (list.none { it.id == view.id }) list.add(view)
        }
        for ((parentId, entries) in subagentCatalogs) {
            for (e in entries) {
                if (e.activity == "running" || e.mode == "continuable") {
                    val title = e.label?.takeIf { it.isNotBlank() }
                        ?: activeSessions.firstOrNull { it.sessionId == e.id }?.title
                        ?: e.id
                    add(parentId, SubagentChildRowView(e.id, title, e.activity == "running"))
                }
            }
        }
        for (s in activeSessions) {
            if (s.isSubagent && s.running && s.parentSessionId != null) {
                add(s.parentSessionId!!, SubagentChildRowView(s.sessionId, s.title, true))
            }
        }
        result
    }

    val scheme = MaterialTheme.colorScheme
    val sem = dev.dsh.remote.ui.theme.LocalDshSemantic.current
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 侧边栏按钮挪到展开后的抽屉顶部；在这里它的作用是收起。
                // 顶栏那个按钮仍然负责"打开"，否则抽屉就没法打开了。
                dev.dsh.remote.ui.theme.GlassIconButton(
                    icon = DshIcons.PanelLeft,
                    contentDescription = Strings.str("collapse_sidebar"),
                    onClick = onCollapse,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "DSH Remote",
                    fontWeight = FontWeight.SemiBold,
                    style = dev.dsh.remote.ui.theme.AppType.wordmark(18),
                    color = scheme.onSurface,
                )
            }
            Row {
                IconButton(onClick = { newWs = null; newPrompt = true }) {
                    DshIcon(DshIcons.NewChat, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 20.dp, contentDescription = Strings.str("new_session"))
                }
                if (newPrompt) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { newPrompt = false },
                        title = { Text("新建会话") },
                        text = { Text("确定要在当前工作区新建一个会话吗？") },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = {
                                newPrompt = false
                                vm.newSession(newWs)
                            }) { Text("新建") }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(onClick = { newPrompt = false }) { Text("取消") }
                        },
                    )
                }
                TextButton(onClick = onCreateWorkspace) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DshIcon(DshIcons.ProjectAdd, tint = MaterialTheme.colorScheme.primary, size = 16.dp)
                        Spacer(Modifier.width(4.dp))
                        Text(Strings.str("workspace"), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(Strings.str("search_conversations")) },
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))

        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenFiles)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DshIcon(DshIcons.Folder, tint = MaterialTheme.colorScheme.tertiary, size = 18.dp)
            Spacer(Modifier.width(8.dp))
            Text(Strings.str("workspace_files"), fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenSubagents)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DshIcon(DshIcons.Fish, tint = scheme.onSurface, size = 20.dp)
            Spacer(Modifier.width(8.dp))
            Text(Strings.str("subagents"), fontWeight = FontWeight.Medium, color = scheme.onSurface)
        }
        // 手机操控：只在设置里打开总开关后出现，避免未启用的用户看到不可用入口。
        val phoneEnabled by vm.phoneControl.collectAsState()
        if (phoneEnabled) {
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenPhone)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DshIcon(DshIcons.Data, tint = scheme.primary, size = 18.dp)
                Spacer(Modifier.width(8.dp))
                Text("手机操控", fontWeight = FontWeight.Medium, color = scheme.onSurface)
            }
        }
        Spacer(Modifier.height(8.dp))

        var searchResults by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
        LaunchedEffect(searchQuery) {
            if (searchQuery.isBlank()) { searchResults = emptyList(); return@LaunchedEffect }
            delay(300)
            vm.searchSessions(searchQuery) { searchResults = it }
        }

        if (searchQuery.isNotBlank()) {
            LazyColumn(Modifier.fillMaxSize()) {
                items(searchResults, key = { it.first }) { (id, snippet) ->
                    val s = sessions.firstOrNull { it.sessionId == id }
                    SessionRow(
                        title = s?.title ?: id,
                        running = s?.running ?: false,
                        selected = id == currentId,
                        onClick = { onSelectSession(id) },
                        onLongClick = { sessionMenuTarget = id to (s?.title ?: id) },
                    )
                    if (snippet.isNotBlank()) {
                        Text(
                            snippet,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
                if (searchResults.isEmpty() && searchQuery.isNotBlank()) {
                    item(key = "no-results") {
                        Text(Strings.str("no_results"), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
                    }
                }
            }
        } else {
            fun addBranch(out: MutableList<SideRow>, s: dev.dsh.remote.data.SessionSummary) {
                val children = childrenByParent[s.sessionId].orEmpty()
                val hasChildren = children.isNotEmpty()
                val expanded = s.sessionId in expandedParents
                out.add(SideRow.Parent(s, hasChildren, expanded))
                if (expanded) for (c in children) out.add(SideRow.Child(s.sessionId, c))
            }
            // 时间戳在 remember 外面取。放在里面会被冻结在第一次组合的时刻,
            // 于是"最近 3 分钟"永远按那一刻算,后来的新会话反而进不去。
            val rowsNow = System.currentTimeMillis()
            val rows = remember(mainSessions, childrenByParent, workspaces, expandedParents) {
                buildList {
                    // 会话按最近活跃降序。宿主给的是 updatedAt 序,但分组是按
                    // workspace 遍历的,组内组间都会乱 —— 新会话于是沉到底部,
                    // 用户滑不到就以为"没显示"。
                    val byRecent = mainSessions.sortedByDescending { it.updatedAt }
                    // 归属判定:**先看 workspace 的 sessionIds,再看目录**。
                    //
                    // 宿主不一定把会话写进 workspace 的 sessionIds —— 有的会话只在
                    // 某个目录下创建,却一直挂在"未分组"里,用户以为它没显示。而
                    // 每个会话都带 cwd、每个 workspace 都带 path,按目录对得上就归位,
                    // 只有目录也不匹配的才留在「其它」。
                    fun workspaceOf(s: SessionSummary): WorkspaceView? {
                        val byId = workspaces.firstOrNull { s.sessionId in it.sessionIds }
                        if (byId != null) return byId
                        val cwd = s.cwd?.trimEnd('\\', '/') ?: return null
                        return workspaces.firstOrNull {
                            val p = it.path.trimEnd('\\', '/')
                            p.isNotEmpty() && p.equals(cwd, ignoreCase = true)
                        }
                    }
                    val grouped = byRecent.map { s -> s to workspaceOf(s) }
                    val orphans = grouped.filter { it.second == null }.map { it.first }
                    // 最近活跃的孤儿提前,免得沉到 100 多条列表的底部。
                    val (fresh, restOrphans) = orphans.partition { rowsNow - it.updatedAt < 3 * 60 * 1000L }
                    if (fresh.isNotEmpty()) {
                        add(SideRow.Header("orphans-fresh", null))
                        fresh.forEach { s -> addBranch(this, s) }
                    }
                    for (ws in workspaces) {
                        val wsSessions = grouped
                            .filter { it.second?.workspaceId == ws.workspaceId }
                            .map { it.first }
                        if (wsSessions.isEmpty()) continue
                        add(SideRow.Header("ws-${ws.workspaceId}", ws))
                        wsSessions.forEach { s -> addBranch(this, s) }
                    }
                    if (restOrphans.isNotEmpty()) {
                        add(SideRow.Header("orphans", null))
                        restOrphans.forEach { s -> addBranch(this, s) }
                    }
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(rows, key = { it.key }) { row ->
                    when (row) {
                        is SideRow.Header -> {
                            if (row.workspace != null) {
                                WorkspaceHeader(
                                    row.workspace,
                                    onLongClick = { workspaceTarget = row.workspace },
                                    onNewSession = { newWs = row.workspace.workspaceId; newPrompt = true },
                                )
                            } else {
                                val sem = dev.dsh.remote.ui.theme.LocalDshSemantic.current
                                Text(
                                    if (row.key == "orphans-fresh") "最近" else Strings.str("other"),
                                    style = dev.dsh.remote.ui.theme.AppType.eyebrow(color = sem.textTertiary),
                                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                                )
                            }
                        }
                        is SideRow.Parent -> {
                            SessionRow(
                                title = row.session.title,
                                running = row.session.running,
                                selected = row.session.sessionId == currentId,
                                onClick = { onSelectSession(row.session.sessionId) },
                                onLongClick = { sessionMenuTarget = row.session.sessionId to row.session.title },
                                hasChildren = row.hasChildren,
                                expanded = row.expanded,
                                onToggleExpand = if (row.hasChildren) {
                                    {
                                        expandedParents = if (row.expanded) expandedParents - row.session.sessionId else expandedParents + row.session.sessionId
                                    }
                                } else null,
                                pendingInteraction = row.session.pendingInteraction,
                            )
                        }
                        is SideRow.Child -> {
                            SubagentChildRow(
                                title = row.child.title,
                                running = row.child.running,
                                selected = row.child.id == currentId,
                                onClick = { onSelectSubagent(row.parentId, row.child.id) },
                            )
                        }
                    }
                }
            }
        }
    }

    sessionMenuTarget?.let { (id, title) ->
        AlertDialog(
            onDismissRequest = { sessionMenuTarget = null },
            title = { Text(title, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
            text = { Text(Strings.str("choose_action")) },
            confirmButton = {
                TextButton(onClick = {
                    renameTarget = id to title
                    sessionMenuTarget = null
                }) { Text(Strings.str("rename")) }
            },
            dismissButton = {
                TextButton(onClick = {
                    vm.deleteSessionById(id)
                    sessionMenuTarget = null
                }) { Text(Strings.str("delete"), color = MaterialTheme.colorScheme.error) }
            },
        )
    }

    renameTarget?.let { (id, oldTitle) ->
        RenameDialog(
            initial = oldTitle,
            onConfirm = { newTitle ->
                vm.renameSession(id, newTitle)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }

    workspaceTarget?.let { ws ->
        AlertDialog(
            onDismissRequest = { workspaceTarget = null },
            title = { Text(ws.title) },
            text = { Text(Strings.str("workspace_actions")) },
            confirmButton = {
                TextButton(onClick = {
                    workspaceRename = ws.workspaceId to ws.title
                    workspaceTarget = null
                }) { Text(Strings.str("rename")) }
            },
            dismissButton = {
                TextButton(onClick = {
                    vm.deleteWorkspace(ws.workspaceId)
                    workspaceTarget = null
                }) { Text(Strings.str("delete"), color = MaterialTheme.colorScheme.error) }
            },
        )
    }

    workspaceRename?.let { (id, oldTitle) ->
        RenameDialog(
            initial = oldTitle,
            onConfirm = { newTitle ->
                vm.renameWorkspace(id, newTitle)
                workspaceRename = null
            },
            onDismiss = { workspaceRename = null },
        )
    }
}

@Composable
private fun RenameDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.str("rename_session")) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }) { Text(Strings.str("ok")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Strings.str("cancel")) } },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WorkspaceHeader(ws: WorkspaceView, onLongClick: () -> Unit, onNewSession: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = ws.title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .weight(1f)
                .combinedClickable(onClick = {}, onLongClick = onLongClick),
        )
        IconButton(onClick = onNewSession, modifier = Modifier.size(28.dp)) {
            DshIcon(
                DshIcons.NewChat,
                tint = MaterialTheme.colorScheme.primary,
                size = 16.dp,
                contentDescription = Strings.str("new_session"),
            )
        }
    }
}

/** One nested subagent child row (id + resolved title + running flag). */
private data class SubagentChildRowView(val id: String, val title: String, val running: Boolean)

/** Flat row descriptor for the sidebar session tree (headers + parents + nested subagent children). */
private sealed interface SideRow {
    val key: String
    data class Header(override val key: String, val workspace: WorkspaceView?) : SideRow
    data class Parent(val session: SessionSummary, val hasChildren: Boolean, val expanded: Boolean) : SideRow {
        override val key: String get() = session.sessionId
    }
    data class Child(val parentId: String, val child: SubagentChildRowView) : SideRow {
        override val key: String get() = "sub-${child.id}"
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    title: String,
    running: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    hasChildren: Boolean = false,
    expanded: Boolean = false,
    onToggleExpand: (() -> Unit)? = null,
    pendingInteraction: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val sem = dev.dsh.remote.ui.theme.LocalDshSemantic.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .clip(RoundedCornerShape(dev.dsh.remote.ui.theme.AppShape.sm))
            .background(
                if (selected) scheme.primary.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent,
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(start = 8.dp, end = 8.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onToggleExpand != null) {
            DshIcon(
                if (expanded) DshIcons.ChevronDown else DshIcons.ChevronRight,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                size = 14.dp,
                modifier = Modifier.clickable(onClick = onToggleExpand),
            )
            Spacer(Modifier.width(2.dp))
        }
        StateDot(
            when {
                running -> "ongoing"
                pendingInteraction != null -> "warning"
                else -> "idle"
            },
            size = 8.dp,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            title,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) scheme.primary else scheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SubagentChildRow(
    title: String,
    running: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
            )
            .padding(start = 30.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DshIcon(DshIcons.Sparkle, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 12.dp)
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(6.dp)
                .background(
                    if (running) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                    CircleShape,
                ),
        )
        Spacer(Modifier.width(8.dp))
        Text(title, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}
