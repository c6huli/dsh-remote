@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.dsh.remote.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextField
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.dsh.remote.data.ChatItem
import dev.dsh.remote.data.ImageData
import dev.dsh.remote.data.ImageRef
import dev.dsh.remote.data.textOf
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import dev.dsh.remote.ui.icons.DshIcon
import dev.dsh.remote.ui.icons.DshIcons
import dev.dsh.remote.ui.theme.pressableClick
import dev.dsh.remote.ui.theme.DshAmber
import dev.dsh.remote.ui.theme.DshGreen
import dev.dsh.remote.ui.theme.DshRed
import dev.dsh.remote.ui.theme.DshUserText

@Composable
fun ChatScreen(
    vm: AppViewModel,
    modifier: Modifier = Modifier,
    onOpenFilesPanel: (() -> Unit)? = null,
    fileChanges: Pair<String, List<String>>? = null,
) {
    val chatItems by vm.chatItems.collectAsState()
    val imageCache by vm.imageCache.collectAsState()
    val running by vm.running.collectAsState()
    val verbose by vm.verbose.collectAsState()
    ChatVerbose.current = verbose
    // Long-press on a file row asks first: one stray tap must not download.
    var pendingFile by remember { mutableStateOf<FileLink?>(null) }
    ChatFiles.confirm = { f -> pendingFile = f }
    // Download runs off the UI thread; the view model owns the coroutine scope.
    ChatFiles.download = { p -> vm.scopeDownload(p) }
    ChatFiles.copyPath = { p -> vm.copyText(p) }
    val models by vm.models.collectAsState()
    val streaming by vm.streaming.collectAsState()
    val streamingReasoning by vm.streamingReasoning.collectAsState()
    val loadingOlder by vm.loadingOlder.collectAsState()
    val loadingSession by vm.loadingSession.collectAsState()
    val currentSessionId by vm.currentSessionId.collectAsState()
    // Only surface decisions that belong to the session currently on screen;
    // the home screen keeps a global "待决策" list so nothing is lost.
    val allApprovals by vm.pendingApprovals.collectAsState()
    val allQuestions by vm.pendingQuestions.collectAsState()
    val pendingApprovals = allApprovals.filter { it.sessionId == currentSessionId }
    val pendingQuestions = allQuestions.filter { it.sessionId == currentSessionId }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Bitmap currently enlarged full-screen (null when nothing is open).
    var fullImage by remember { mutableStateOf<Bitmap?>(null) }

    val totalItems = chatItems.size +
        (if (streaming.isNotEmpty() || streamingReasoning.isNotEmpty()) 1 else 0) +
        pendingApprovals.size + pendingQuestions.size
    val followTail = remember { mutableStateOf(true) }

    // Follow the tail only while the user is parked at the bottom. The moment
    // they scroll away it unlocks; scrolling back to the very end re-locks.
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            val atEnd = last != null && last.index >= info.totalItemsCount - 1 &&
                last.offset + last.size <= info.viewportEndOffset + 1
            atEnd to listState.isScrollInProgress
        }
            .distinctUntilChanged()
            .collect { (atEnd, scrolling) ->
                if (atEnd) followTail.value = true
                else if (scrolling) followTail.value = false
            }
    }

    LaunchedEffect(chatItems.size, streaming.length, streamingReasoning.length, pendingApprovals.size, pendingQuestions.size) {
        if (totalItems > 0 && followTail.value) listState.scrollToBottom()
    }

    // Load older history when the user scrolls to the top.
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { index -> if (index <= 1) vm.loadOlder() }
    }

    // Entering the chat (fresh composition or right after a session loads) → jump to bottom.
    LaunchedEffect(loadingSession) {
        if (!loadingSession) {
            snapshotFlow { listState.layoutInfo.totalItemsCount }
                .first { it > 0 }
            listState.scrollToBottom()
        }
    }

    val showJumpToBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            if (total == 0) return@derivedStateOf false
            val last = info.visibleItemsInfo.lastOrNull()
            val atEnd = last != null && last.index >= total - 1 &&
                last.offset + last.size <= info.viewportEndOffset + 1
            !atEnd
        }
    }

    if (loadingSession) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
                Text(Strings.str("loading"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    // Keyboard-aware: the composer must ride above the IME instead of being hidden.
    Column(modifier.fillMaxSize().imePadding()) {
        // Consecutive tool calls are folded into one block, so a turn reads as
        // "a few steps then the answer" instead of an endless column of cards.
        // 文件卡的「查看改动」按路径找回 diff,所以先登记本轮所有带 diff 的工具调用。
        remember(chatItems) {
            ChatDiffRegistry.reset()
            chatItems.forEach { if (it is ChatItem.Tool) ChatDiffRegistry.register(it) }
            true
        }
        val rows = remember(chatItems) { groupToolSteps(chatItems) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            ) {
                if (loadingOlder) {
                    item(key = "loading-older") {
                        Text(Strings.str("load_earlier"), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
                // 最后一张过程卡才显示"执行中":历史轮的卡片早已结束。
                val lastProcessIndex = rows.indexOfLast { it is ChatRow.Process }
                itemsIndexed(rows) { index, row ->
                    when (row) {
                        is ChatRow.Process -> TurnProcessCard(
                            items = row.items,
                            running = running && index == lastProcessIndex,
                            fullReasoning = vm::fullReasoning,
                        )
                        is ChatRow.Tools -> ToolGroupCard(row.items, running)
                        is ChatRow.Files -> TurnFilesCard(row.files)
                        is ChatRow.Single -> ChatRow(
                            row.item,
                            vm::fullReasoning,
                            onFork = { seq -> vm.forkSession(seq) },
                            onCopy = { text -> vm.copyText(text) },
                            imageCache = imageCache,
                            onOpenImage = { bmp -> fullImage = bmp },
                            running = running,
                        )
                    }
                }
                if (streamingReasoning.isNotEmpty() || streaming.isNotEmpty()) {
                    item(key = "streaming") {
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            if (streamingReasoning.isNotEmpty()) {
                                ReasoningBox(
                                    lastLineSummary(streamingReasoning),
                                    onExpanded = { scope.launch { listState.scrollToBottom() } },
                                ) { streamingReasoning }
                            }
                            if (streaming.isNotEmpty()) MarkdownText(streaming)
                        }
                    }
                }
                // Inline decisions: plan review / ask-user questions / approvals sit in
                // the conversation flow (below the messages) rather than covering them.
                items(pendingApprovals, key = { "pending-a-${it.rpcId}" }) { a ->
                    ApprovalCard(vm, a)
                }
                items(pendingQuestions, key = { "pending-q-${it.rpcId}" }) { q ->
                    val first = q.questions.firstOrNull()
                    if (first != null && first.intent?.kind == "plan-review" && first.detail != null) {
                        PlanReviewCard(vm, q, first)
                    } else {
                        QuestionCard(vm, q)
                    }
                }
            }
            if (showJumpToBottom) {
                dev.dsh.remote.ui.theme.GlassSurface(
                    tier = dev.dsh.remote.ui.theme.GlassTier.Chrome,
                    shape = CircleShape,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp)
                        .size(40.dp),
                    onClick = { scope.launch { listState.scrollToBottom() } },
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        DshIcon(
                            DshIcons.ChevronDown,
                            tint = MaterialTheme.colorScheme.primary,
                            size = 20.dp,
                            contentDescription = Strings.str("jump_bottom"),
                        )
                    }
                }
            }
        }
        if (running) RunStatusBar(vm)
        // Always-on cost/throughput strip: tokens per second, how full the context is
        // and how much of the prompt came from cache. The web UI shows these during a
        // run; here they stay put so they can be checked at any moment.
        StatsStrip(vm)
        // dsh's own end-of-turn summary: how much the turn produced and what it
        // touched. Shown inline at the bottom of the conversation, like the web UI.
        if (!running && chatItems.isNotEmpty()) {
            TurnSummary(vm, fileChanges)
        }
        Composer(
            vm = vm,
            currentModel = models?.current?.model ?: Strings.str("choose_model"),
            running = running,
        )
    }


    // Download confirmation: long-press a file row to get here.
    pendingFile?.let { f ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingFile = null },
            title = { Text("下载文件") },
            text = {
                Text(
                    f.path.substringAfterLast('/').toString() + "\n\n保存到 下载/DSHRemote/",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    ChatFiles.download?.invoke(f.path)
                    pendingFile = null
                }) { Text("确认下载") }
            },
            dismissButton = {
                TextButton(onClick = { pendingFile = null }) { Text("取消") }
            },
        )
    }
    // Full-screen image viewer: click a thumbnail to enlarge, tap outside to close.
    if (fullImage != null) {
        Dialog(
            onDismissRequest = { fullImage = null },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            Box(
                Modifier.fillMaxSize().background(Color(0xCC000000)),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = fullImage!!.asImageBitmap(),
                    contentDescription = Strings.str("image_preview"),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().fillMaxHeight().clickable { fullImage = null },
                )
            }
        }
    }
}

private const val SCROLL_BOTTOM_OFFSET = 1_000_000_000

private data class PendingImage(val mediaType: String, val base64: String, val name: String?, val bitmap: Bitmap?)

private fun decodeThumbnail(bytes: ByteArray, maxDim: Int = 256): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
}

private suspend fun LazyListState.scrollToBottom() {
    val last = layoutInfo.totalItemsCount - 1
    if (last >= 0) scrollToItem(last, scrollOffset = SCROLL_BOTTOM_OFFSET)
}

/**
 * Bottom card shown once a turn has finished.
 *
 * Mirrors the desktop rail: "已编辑 N 个文件 +x −y" plus the changed paths, tapping
 * opens the right panel on its 文件变动 view. Falls back to a plain entry when the
 * numbers have not been reported by the embedded panel yet.
 */
@Composable
private fun TurnFooter(summary: String?, files: List<String>, onOpenPanel: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenPanel)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            DshIcon(DshIcons.Check, tint = MaterialTheme.colorScheme.secondary, size = 14.dp, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(
                summary ?: "本轮已结束",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Text(
                "查看",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            DshIcon(DshIcons.ChevronRight, tint = MaterialTheme.colorScheme.primary, size = 14.dp, contentDescription = null)
        }
        files.take(3).forEach { path ->
            Text(
                path,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 20.dp, top = 2.dp),
            )
        }
        if (files.size > 3) {
            Text(
                "…等 ${files.size} 个文件",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, top = 2.dp),
            )
        }
    }
}

/** Set by ChatScreen from the 详细/简洁 preference; read when rendering each item. */
private object ChatVerbose {
    var current: Boolean = false
}

/**
 * The turn's own result line, matching what dsh shows when a run finishes: what the
 * model produced, how much came from cache, how full the context is, and which files
 * the turn touched. Purely informational — tapping it opens the sidebar for detail.
 */
@Composable
private fun TurnSummary(vm: AppViewModel, fileChanges: Pair<String, List<String>>?) {
    val usage by vm.turnUsageFlow.collectAsState()
    val pressure by vm.currentContextPressure.collectAsState()
    val u = usage ?: return
    val hitPct = if (u.totalInput > 0) u.cacheReadTokens * 100.0 / u.totalInput else null
    val ctxPct = pressure?.let { p ->
        if (p.contextWindow > 0) {
            p.projectedTokens.coerceAtLeast(p.pressureTokens) * 100.0 / p.contextWindow
        } else null
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp),
    ) {
        Text(
            buildString {
                append("输出 ")
                append(dev.dsh.remote.data.fmtTokens(u.outputTokens))
                append(" tokens")
                if (hitPct != null) append(" · 缓存 %.0f%%".format(hitPct))
                if (ctxPct != null) append(" · 上下文 %.0f%%".format(ctxPct))
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        fileChanges?.first?.let { summary ->
            Text(
                summary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReasoningBox(
    summary: String,
    onExpanded: () -> Unit = {},
    initiallyExpanded: Boolean = false,
    fullProvider: () -> String,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    var fullText by remember { mutableStateOf<String?>(null) }
    val sem = dev.dsh.remote.ui.theme.LocalDshSemantic.current
    val scheme = MaterialTheme.colorScheme
    // 折叠动画:高度变化走 animateContentSize,慢一拍才不"咔"一下。
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(dev.dsh.remote.ui.theme.AppShape.sm))
            .background(scheme.onSurface.copy(alpha = 0.04f))
            .pressableClick(onClick = {
                expanded = !expanded
                if (expanded) {
                    if (fullText == null) fullText = fullProvider()
                    onExpanded()
                }
            })
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DshIcon(DshIcons.Think, tint = sem.textTertiary, size = 14.dp)
            Spacer(Modifier.width(6.dp))
            Text(
                Strings.str("reasoning"),
                color = sem.textTertiary,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (expanded) Strings.str("collapse") else Strings.str("expand"),
                color = sem.textTertiary,
                style = MaterialTheme.typography.labelSmall,
            )
            DshIcon(
                if (expanded) DshIcons.ChevronUp else DshIcons.ChevronDown,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                size = 12.dp,
            )
        }
        Spacer(Modifier.height(2.dp))
        if (expanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    fullText ?: summary,
                    color = scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        } else {
            Text(
                summary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

private fun lastLineSummary(text: String): String {
    val line = text.lines().lastOrNull { it.isNotBlank() } ?: ""
    return if (line.length > 80) line.take(80) + "…" else line
}

@Composable
private fun ModeRow(vm: AppViewModel) {
    val plan by vm.currentPlan.collectAsState()
    val permissions by vm.currentPermissions.collectAsState()
    var permMenu by remember { mutableStateOf(false) }
    var cmdMenu by remember { mutableStateOf(false) }
    val sessionId by vm.currentSessionId.collectAsState()
    val commands by vm.commands.collectAsState()
    // Refresh the actionable command list whenever the open session changes.
    LaunchedEffect(sessionId) { if (sessionId != null) vm.loadCommands() }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box {
            Row(
                Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                    .clickable { cmdMenu = true }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    Strings.str("commands"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                DshIcon(
                    DshIcons.ChevronDown,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    size = 14.dp,
                )
            }
            DropdownMenu(expanded = cmdMenu, onDismissRequest = { cmdMenu = false }) {
                if (commands.isEmpty()) {
                    CommandItem("/plan", Strings.str("cmd_plan") + if (plan?.active == true) Strings.str("plan_active") else "") {
                        vm.insertCommand(if (plan?.active == true) "/plan off" else "/plan")
                        cmdMenu = false
                    }
                    CommandItem("/goal", Strings.str("cmd_goal")) {
                        vm.insertCommand("/goal")
                        cmdMenu = false
                    }
                    CommandItem("/compact", Strings.str("cmd_compact")) {
                        vm.insertCommand("/compact")
                        cmdMenu = false
                    }
                } else {
                    // Every host command is an action that runs immediately.
                    for (c in commands) {
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text("/${c.name}", style = MaterialTheme.typography.bodyMedium)
                                    if (c.description.isNotBlank()) {
                                        Text(
                                            c.description,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            },
                            onClick = {
                                vm.executeCommand("/${c.name}")
                                cmdMenu = false
                            },
                        )
                    }
                }
            }
        }
        Box {
            Row(
                Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                    .clickable { permMenu = true }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    permissions?.currentValue ?: Strings.str("permissions"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                DshIcon(
                    DshIcons.ChevronDown,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    size = 14.dp,
                )
            }
            DropdownMenu(expanded = permMenu, onDismissRequest = { permMenu = false }) {
                for (opt in permissions?.options.orEmpty()) {
                    DropdownMenuItem(
                        text = { Text(opt.name) },
                        onClick = {
                            sessionId?.let { vm.setPermission(it, opt.value) }
                            permMenu = false
                        },
                    )
                }
            }
        }
    }
}

/** Highlights leading slash-command tokens (e.g. "/plan") in amber inside the composer. */
private class SlashCommandTransformation(val color: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val builder = AnnotatedString.Builder()
        var i = 0
        while (i < text.length) {
            if (text[i] == '/') {
                var j = i + 1
                while (j < text.length && !text[j].isWhitespace()) j++
                builder.pushStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold))
                builder.append(text.subSequence(i, j))
                builder.pop()
                i = j
            } else {
                var j = i
                while (j < text.length && text[j] != '/') j++
                builder.append(text.subSequence(i, j))
                i = j
            }
        }
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }
}

@Composable
private fun CommandItem(name: String, description: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Column {
                Text(name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                Text(
                    description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        },
        onClick = onClick,
    )
}

/** Render chat-message images as tappable thumbnails; the decoded bitmap comes from the cache. */
@Composable
private fun ChatImages(
    refs: List<ImageRef>,
    imageCache: Map<String, Bitmap>,
    onOpenImage: (Bitmap) -> Unit,
) {
    if (refs.isEmpty()) return
    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (ref in refs) {
            val bmp = imageCache[ref.attachmentId]
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = ref.name ?: "image",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onOpenImage(bmp) },
                )
            } else {
                // Placeholder while the attachment is still loading (or failed).
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        Strings.str("image_loading"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatRow(
    item: ChatItem,
    fullReasoningOf: (Long) -> String,
    onFork: (Long) -> Unit,
    onCopy: (String) -> Unit,
    imageCache: Map<String, Bitmap>,
    onOpenImage: (Bitmap) -> Unit,
    running: Boolean = false,
) {
    when (item) {
        is ChatItem.User -> {
            // 用户气泡:比页面深一档的主色系,像轻轻按进纸里的一块(不是纯色块)。
            val surfaces = dev.dsh.remote.ui.theme.LocalDshSurfaces.current
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Box(
                    Modifier
                        .clip(
                            RoundedCornerShape(
                                topStart = dev.dsh.remote.ui.theme.AppShape.lg,
                                topEnd = dev.dsh.remote.ui.theme.AppShape.lg,
                                bottomEnd = dev.dsh.remote.ui.theme.AppShape.xs,
                                bottomStart = dev.dsh.remote.ui.theme.AppShape.lg,
                            ),
                        )
                        .background(surfaces.userBubble)
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                        .widthIn(max = 320.dp),
                ) {
                    Column {
                        if (item.text.isNotBlank()) {
                            MarkdownText(item.text, color = surfaces.onUserBubble)
                        }
                        ChatImages(item.images, imageCache, onOpenImage)
                    }
                }
            }
        }
        is ChatItem.Assistant -> {
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                if (item.reasoning.isNotBlank()) {
                    ReasoningBox(item.reasoning, initiallyExpanded = ChatVerbose.current) { fullReasoningOf(item.seq) }
                    Spacer(Modifier.height(4.dp))
                }
                if (item.text.isNotBlank()) {
                    MarkdownText(item.text)
                }
                ChatImages(item.images, imageCache, onOpenImage)
                // Fork / copy only after the LAST output of a turn.
                if (item.isTurnEnd) {
                    Row(
                        Modifier.padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                                .clickable { onFork(item.seq) }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            DshIcon(DshIcons.Branch, tint = MaterialTheme.colorScheme.primary, size = 14.dp)
                            Spacer(Modifier.width(4.dp))
                            Text(
                                Strings.str("fork"),
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                                .clickable { onCopy(item.text) }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            DshIcon(DshIcons.Copy, tint = MaterialTheme.colorScheme.primary, size = 14.dp)
                            Spacer(Modifier.width(4.dp))
                            Text(
                                Strings.str("copy"),
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
        is ChatItem.Tool -> {
            ToolCard(item, running)
        }
        is ChatItem.Todo -> {
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                for (t in item.items) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .background(
                                    when (t.status) {
                                        "completed" -> DshGreen
                                        "in_progress" -> DshAmber
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    CircleShape,
                                ),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            t.content,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        is ChatItem.Meta -> {
            Text(
                item.text,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        is ChatItem.SystemDetail -> {
            SystemDetailRow(item)
        }
        is ChatItem.Cost -> {
            Text(
                item.text,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            )
        }
        is ChatItem.Error -> ModelErrorCard(item)
        is ChatItem.Deliverables -> {
            DeliverablesRow(item.paths)
        }
    }
}

/** Collapsible one-line notice for large host-injected payloads (compaction
 * checkpoints, skill catalog, runtime context). Tap to expand the full text. */
@Composable
private fun SystemDetailRow(item: ChatItem.SystemDetail) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(dev.dsh.remote.ui.theme.AppShape.sm))
            .clickable { expanded = !expanded }
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DshIcon(
                DshIcons.Think,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                size = 14.dp,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                item.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("system-detail", item.text))
                        android.widget.Toast.makeText(context, Strings.str("copied"), android.widget.Toast.LENGTH_SHORT).show()
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                DshIcon(DshIcons.Copy, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 12.dp)
                Spacer(Modifier.width(4.dp))
                Text(
                    Strings.str("copy"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Text(
                Strings.str(if (expanded) "sys_detail_hide" else "sys_detail_show"),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (expanded) {
            Text(
                item.text,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeliverablesRow(paths: List<String>) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)) {
        Text(
            Strings.str("deliverables"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (p in paths.take(6)) {
                Text(
                    p.substringAfterLast('/').substringAfterLast('\\'),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier
                        .widthIn(max = 220.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            if (paths.size > 6) {
                Text(
                    Strings.str("more_files", paths.size - 6),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

/** Red failure card for a model request that failed (turn/end reason.kind == "error"). */
@Composable
private fun ModelErrorCard(item: ChatItem.Error) {
    val wayne = dev.dsh.remote.ui.theme.LocalDshSemantic.current
    val context = LocalContext.current
    val requestId = item.requestId
    val detail = buildString {
        if (item.code.isNotBlank()) append(item.code)
        if (item.status > 0) append(if (isNotEmpty()) " · " else "").append("HTTP ${item.status}")
        if (requestId.isNotBlank()) append(if (isNotEmpty()) " · " else "").append(requestId)
    }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(dev.dsh.remote.ui.theme.AppShape.md))
            .background(wayne.dangerContainer)
            .clickable {
                val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("model-error", item.message))
                android.widget.Toast.makeText(context, Strings.str("copied"), android.widget.Toast.LENGTH_SHORT).show()
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DshIcon(DshIcons.Warning, tint = wayne.onDangerContainer, size = 16.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    Strings.str("model_failed"),
                    color = wayne.onDangerContainer,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                item.message,
                color = wayne.onDangerContainer,
                style = MaterialTheme.typography.bodySmall,
            )
            if (detail.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    detail,
                    color = wayne.onDangerContainer.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

private fun toolIcon(card: String?, name: String): Int = when (card) {
    "diff" -> DshIcons.Edit
    "terminal" -> DshIcons.Api
    "search" -> DshIcons.Search
    "web" -> DshIcons.Globe
    "fs" -> DshIcons.Browse
    else -> when {
        name.startsWith("edit") || name.startsWith("write") || name.startsWith("str_replace") -> DshIcons.Edit
        name.startsWith("read") || name == "glob" || name.startsWith("list") -> DshIcons.Browse
        name == "bash" || name == "pwsh" || name.startsWith("terminal") || name.startsWith("subagent") -> DshIcons.Api
        name.startsWith("search") || name.startsWith("grep") -> DshIcons.Search
        name.startsWith("web") || name == "fetch" -> DshIcons.Globe
        else -> DshIcons.Sparkle
    }
}

/** One rendered row: either a chat item as-is, or a run of tool steps folded together. */
private sealed interface ChatRow {
    data class Single(val item: ChatItem) : ChatRow
    data class Tools(val items: List<ChatItem.Tool>) : ChatRow
    /**
     * 一整轮的过程:思考与工具调用按发生顺序放在一张卡里。
     *
     * 之前只有"连续的多个工具"才会合并,中间夹一条思考就断成两张卡,一輪跑下来
     * 屏幕上是一串各说各话的小块。这里按"轮"聚合,完成即自动折叠成一行摘要。
     */
    data class Process(val items: List<ChatItem>) : ChatRow
    /** Files one turn touched, rendered inline right after that turn. */
    data class Files(val files: TurnFiles) : ChatRow
}

/**
 * One turn's files, split the way the Web UI splits them.
 *
 * [presented] are what the agent handed over (`present` / dsh deliverable cards),
 * [edited] is everything else it wrote on the way. Edited files are usually the
 * long tail of a turn's work, so the card keeps them collapsed.
 */
private data class TurnFiles(
    val presented: List<FileLink> = emptyList(),
    val edited: List<FileLink> = emptyList(),
) {
    val isEmpty: Boolean get() = presented.isEmpty() && edited.isEmpty()
    /** Every path in the card, for the per-turn dedupe. */
    fun paths(): Sequence<String> = (presented.asSequence() + edited.asSequence()).map { it.path }

    /**
     * 增删行数。只统计有 diff 的文件 —— 没有 diff 的文件(纯交付、或只被读取)
     * 记 0,而不是编一个数出来。
     */
    val addedLines: Int get() = (presented + edited).sumOf { it.added }
    val removedLines: Int get() = (presented + edited).sumOf { it.removed }
}

/** Rows shown in a turn's file card before collapsing the rest into a `+N` hint. */
private const val MAX_CARD_ROWS = 8

/**
 * Fold every run of consecutive tool steps into a single row.
 *
 * A single step stays on its own (there is nothing to fold), which keeps simple
 * answers looking exactly as before.
 */
/**
 * 把一串会话项折成可渲染的行。
 *
 * 关键规则:**一轮 = 一条用户消息到下一轮开始之间的过程**,期间的思考与工具调用
 * 按发生顺序进同一个 [ChatRow.Process];最后的回答文本单独一行(它是给人读的,
 * 不该被折进"过程"里);这一轮碰过的文件在该轮结束时单独出一张卡。
 */
private fun groupToolSteps(items: List<ChatItem>): List<ChatRow> {
    val out = ArrayList<ChatRow>(items.size)
    var process = ArrayList<ChatItem>()
    // Files this turn touched: flushed at the end of each turn, so every turn — and
    // every historical session — gets its own downloadable card. This deliberately
    // does NOT depend on the agent calling `present`: any file a tool wrote or
    // edited is listed too, which is what makes the card appear for every session.
    var presented = ArrayList<FileLink>()
    var edited = ArrayList<FileLink>()
    // A tool call and its result both describe the same file, so dedupe inside the
    // turn. Across turns a file may legitimately reappear (it changed again), which
    // is information worth keeping.
    var turnSeen = HashSet<String>()
    fun flushProcess() {
        if (process.isNotEmpty()) {
            out.add(ChatRow.Process(process.toList()))
            process = ArrayList()
        }
    }
    fun flushFiles() {
        val files = TurnFiles(presented.toList(), edited.toList())
        if (!files.isEmpty) out.add(ChatRow.Files(files))
        presented = ArrayList()
        edited = ArrayList()
        turnSeen = HashSet()
    }
    fun trackFiles(item: ChatItem.Tool) {
        val (delivered, touched) = filesOfTool(item)
        for (f in touched) if (turnSeen.add(f.path)) edited.add(f)
        // A handed-over file wins over a later "edited" entry for the same path.
        for (f in delivered) {
            if (turnSeen.add(f.path)) presented.add(f) else {
                edited.removeAll { it.path == f.path }
                if (presented.none { it.path == f.path }) presented.add(f)
            }
        }
    }
    for (item in items) {
        when {
            item is ChatItem.Tool -> {
                process.add(item)
                trackFiles(item)
            }
            // 只有"纯思考"的助手项进过程;带正文的是回答,单独成行。
            item is ChatItem.Assistant && item.text.isBlank() && item.reasoning.isNotBlank() -> {
                process.add(item)
            }
            item is ChatItem.Assistant -> {
                flushProcess()
                out.add(ChatRow.Single(item))
                if (item.isTurnEnd) {
                    flushProcess()
                    flushFiles()
                }
            }
            // A user message is a hard boundary too, in case a turn never ended
            // cleanly (aborted run) — otherwise its files would leak into the next.
            item is ChatItem.User -> {
                flushProcess()
                flushFiles()
                out.add(ChatRow.Single(item))
            }
            else -> {
                flushProcess()
                out.add(ChatRow.Single(item))
            }
        }
    }
    flushProcess()
    flushFiles()
    return out
}

/** Tools whose arguments name the file they write, even without a `files` array. */
private val FILE_WRITING_TOOLS = setOf(
    "write", "edit", "multi_edit", "apply_patch", "create_file", "replace_in_file",
    "str_replace", "notebook_edit", "present",
)

/**
 * Every file one tool touched, split into handed-over and merely-edited.
 *
 * Three sources, so a card exists whether or not the agent called `present`:
 *  - a `files` array (the `present` tool, and dsh's own deliverable cards)
 *  - a diff card's path
 *  - the `path` / `file_path` argument of a writing tool
 *
 * Only `present` (and a delivered `files` array) counts as a final deliverable;
 * write/edit style tools produced the working set, which the card keeps folded.
 */
private fun filesOfTool(t: ChatItem.Tool): Pair<List<FileLink>, List<FileLink>> {
    val isPresent = t.name.equals("present", ignoreCase = true)
    val delivered = ArrayList<FileLink>(2)
    val edited = ArrayList<FileLink>(2)
    // diff 的增删行数:有 diff 才有真实数字,给文件行显示 +N。
    val diffStat = t.diffOld?.let { old -> t.diffNew?.let { new -> diffStatOf(old, new) } }
    if (isPresent) {
        delivered.addAll(parseFileLinks(t.card, t.arguments))
    } else {
        edited.addAll(parseFileLinks(t.card, t.arguments))
    }
    t.diffPath?.takeIf { it.isNotBlank() }?.let {
        edited.add(
            FileLink(
                path = it,
                label = labelOf(it),
                added = diffStat?.first ?: 0,
                removed = diffStat?.second ?: 0,
            ),
        )
    }
    if (t.name.lowercase() in FILE_WRITING_TOOLS && !isPresent) {
        pathOfToolArguments(t.arguments)?.let { edited.add(FileLink(it, labelOf(it))) }
    }
    return delivered to edited
}

private fun labelOf(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')

/** 一次改动新增 / 删除多少行(复用 [computeDiff],口径与 diff 视图一致)。 */
private fun diffStatOf(oldText: String, newText: String): Pair<Int, Int> {
    val lines = computeDiff(oldText, newText)
    val added = lines.count { it is DiffLine.Added }
    val removed = lines.count { it is DiffLine.Removed }
    return added to removed
}

/** Pull `path` / `file_path` out of a tool-call arguments JSON object. */
private fun pathOfToolArguments(arguments: String?): String? {
    val body = arguments?.takeIf { it.isNotBlank() } ?: return null
    return try {
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject
            ?: return null
        listOf("path", "file_path", "filePath", "filename", "file").firstNotNullOfOrNull { key ->
            (obj[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * Full model picker: a scrollable, searchable dialog.
 *
 * Plugins can register thousands of entries, which a DropdownMenu cannot render
 * (it would silently drop most of them), so this uses a lazy list instead.
 */
@Composable
private fun ModelPickerDialog(
    groups: List<dev.dsh.remote.data.ModelGroup>,
    onDismiss: () -> Unit,
    onPick: (String, String, String?) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    // Providers start collapsed: plugin installs can add dozens, and a flat list of
    // thousands is unusable. A search overrides the fold and expands matches.
    var expanded by remember { mutableStateOf(setOf<String>()) }

    val q = query.trim().lowercase()
    val searching = q.isNotEmpty()
    val groups2 = remember(groups, q) {
        groups.map { g ->
            val ms = g.models.filter { m ->
                !searching || m.name.lowercase().contains(q) ||
                    m.id.lowercase().contains(q) || g.name.lowercase().contains(q)
            }
            g to ms
        }.filter { (_, ms) -> ms.isNotEmpty() }
    }
    val totalModels = groups.sumOf { it.models.size }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                .padding(12.dp),
        ) {
            Text("选择模型", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
            Text(
                "${groups.size} 个提供方 · $totalModels 个模型",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            androidx.compose.material3.OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("搜索模型 / 提供方…") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(groups2.size) { i ->
                    val (g, ms) = groups2[i]
                    val open = searching || g.id in expanded
                    // Provider row: name + count, tap to fold/unfold.
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                expanded = if (g.id in expanded) expanded - g.id else expanded + g.id
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DshIcon(
                            if (open) DshIcons.ChevronDown else DshIcons.ChevronRight,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            size = 14.dp,
                            contentDescription = null,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            g.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${ms.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (open) {
                        for (m in ms) {
                            Text(
                                m.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(g.id, m.id, m.reasoning?.defaultEffort) }
                                    .padding(start = 26.dp, top = 7.dp, bottom = 7.dp),
                            )
                        }
                    }
                }
                if (groups2.isEmpty()) {
                    item {
                        Text(
                            if (searching) "没有匹配的模型" else "模型加载中…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    }
}

/**
 * A folded run of tool steps.
 * While the turn is running the steps are listed one line each, so progress stays
 * visible; once it ends the block collapses to a single summary line and can be
 * expanded again by tapping.
 */
/**
 * 一轮的过程卡:标题行 + 每步一行的紧凑列表。
 *
 * 视觉规则(对齐参考实现):
 * - 整块是**一张**淡色卡,卡里的每一步不再各自带背景,否则一輪下来是十几个色块;
 * - 每步 = 圆角图标块(按类别着色)+ 类别名 + 单行摘要 + 展开箭头;
 * - 跑完自动折叠成一行摘要,点一下才展开细节。
 */
@Composable
private fun TurnProcessCard(
    items: List<ChatItem>,
    running: Boolean,
    fullReasoning: (Long) -> String,
) {
    val sem = dev.dsh.remote.ui.theme.LocalDshSemantic.current
    val scheme = MaterialTheme.colorScheme
    val toolCount = items.count { it is ChatItem.Tool }
    val messageCount = items.count { it is ChatItem.Assistant }
    val hasError = items.any { it is ChatItem.Tool && it.isError }
    // 正在跑就展开、跑完自动折叠;历史轮进来时 running=false,直接是折叠态。
    // key 只认这一轮的第一项:流式追加不会改变它,否则每来一个新步骤就把"展开"重置。
    val firstSeq = items.firstOrNull()?.let { seqOf(it) }
    var expanded by remember(firstSeq) { mutableStateOf(running) }
    // "正在跑"要能覆盖它:新一轮开始时自动展开一次,跑完再自动收起。
    var autoExpanded by remember(firstSeq) { mutableStateOf(false) }
    LaunchedEffect(running) {
        if (running && !autoExpanded) {
            expanded = true
            autoExpanded = true
        }
    }
    LaunchedEffect(running) { if (!running) expanded = false }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(dev.dsh.remote.ui.theme.AppShape.lg))
            .background(scheme.onSurface.copy(alpha = 0.03f)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .pressableClick(onClick = { expanded = !expanded })
                .padding(start = 12.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (running) {
                CircularProgressIndicator(
                    modifier = Modifier.size(15.dp),
                    strokeWidth = 1.6.dp,
                    color = scheme.primary,
                )
            } else {
                DshIcon(
                    DshIcons.Check,
                    tint = if (hasError) sem.danger else sem.textTertiary,
                    size = 15.dp,
                    contentDescription = null,
                )
            }
            Spacer(Modifier.width(9.dp))
            Text(
                buildString {
                    append(if (running) "执行中" else "已完成")
                    if (toolCount > 0) append(" · $toolCount 次工具调用")
                    if (messageCount > 0) append(" · $messageCount 条消息")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            DshIcon(
                if (expanded) DshIcons.ChevronUp else DshIcons.ChevronDown,
                tint = sem.textTertiary,
                size = 16.dp,
                contentDescription = null,
            )
        }
        // 展开用 AnimatedVisibility:高度变化有过渡,不是"咔"一下跳开。
        androidx.compose.animation.AnimatedVisibility(
            visible = expanded,
            enter = androidx.compose.animation.expandVertically(
                animationSpec = androidx.compose.animation.core.tween(
                    dev.dsh.remote.ui.theme.AppMotion.collapseMs,
                    easing = dev.dsh.remote.ui.theme.AppMotion.std,
                ),
            ) + androidx.compose.animation.fadeIn(
                animationSpec = androidx.compose.animation.core.tween(dev.dsh.remote.ui.theme.AppMotion.quickMs),
            ),
            exit = androidx.compose.animation.shrinkVertically(
                animationSpec = androidx.compose.animation.core.tween(
                    dev.dsh.remote.ui.theme.AppMotion.standardMs,
                    easing = dev.dsh.remote.ui.theme.AppMotion.std,
                ),
            ) + androidx.compose.animation.fadeOut(
                animationSpec = androidx.compose.animation.core.tween(dev.dsh.remote.ui.theme.AppMotion.quickMs),
            ),
        ) {
            Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = 10.dp, bottom = 8.dp)) {
                items.forEach { item ->
                    ProcessRow(item, fullReasoning)
                }
            }
        }
    }
}

/** 一条过程项的时间戳 / 唯一键。 */
private fun seqOf(item: ChatItem): Long = when (item) {
    is ChatItem.User -> item.seq
    is ChatItem.Assistant -> item.seq
    is ChatItem.Tool -> item.seq
    is ChatItem.Meta -> item.seq
    is ChatItem.SystemDetail -> item.seq
    is ChatItem.Cost -> item.seq
    else -> 0L
}

/** 工具类别 → 中文短标签(与图标一致)。 */
private fun toolLabelOf(kind: ToolKind): String = when (kind) {
    ToolKind.Shell -> "运行"
    ToolKind.Edit -> "编辑"
    ToolKind.Read -> "读取"
    ToolKind.Web -> "联网"
    ToolKind.Task -> "计划"
    ToolKind.Media -> "媒体"
    ToolKind.Other -> "工具"
}

/**
 * 过程卡里的一行:图标块 + 标签 + 单行摘要 + 箭头;点开看细节。
 *
 * 思考项展开的是完整思考文本,工具项展开的是参数 / diff —— 与工具卡一致。
 */
@Composable
private fun ProcessRow(item: ChatItem, fullReasoning: (Long) -> String) {
    val sem = dev.dsh.remote.ui.theme.LocalDshSemantic.current
    val scheme = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    val kind = when (item) {
        is ChatItem.Tool -> toolKindOf(item.name)
        else -> ToolKind.Task
    }
    val isError = item is ChatItem.Tool && item.isError
    val (badgeBg, badgeFg) = if (isError) {
        sem.dangerContainer to sem.onDangerContainer
    } else {
        sem.tool(toolToneOf(kind))
    }
    val label = when (item) {
        is ChatItem.Tool -> toolLabelOf(kind)
        else -> "思考"
    }
    val icon = when (item) {
        is ChatItem.Tool -> toolIcon(item.card, item.name)
        else -> DshIcons.Think
    }
    val summary = when (item) {
        is ChatItem.Tool -> item.summary
            ?: item.arguments.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(120)
            ?: ""
        is ChatItem.Assistant -> lastLineSummary(item.reasoning)
        else -> ""
    }
    val expandable = when (item) {
        is ChatItem.Tool -> item.arguments.isNotBlank() || (item.diffOld != null && item.diffNew != null)
        is ChatItem.Assistant -> item.reasoning.isNotBlank()
        else -> false
    }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(dev.dsh.remote.ui.theme.AppShape.sm))
                .then(
                    if (expandable) Modifier.pressableClick(onClick = { expanded = !expanded })
                    else Modifier,
                )
                .padding(horizontal = 6.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 圆角图标块:类别色的淡底 + 同类前景,一眼能分出"在读还是在跑"。
            Box(
                Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(dev.dsh.remote.ui.theme.AppShape.sm))
                    .background(badgeBg),
                contentAlignment = Alignment.Center,
            ) {
                DshIcon(icon, tint = badgeFg, size = 15.dp, contentDescription = null)
            }
            Spacer(Modifier.width(10.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
                maxLines = 1,
            )
            if (summary.isNotBlank()) {
                Text(
                    " · ",
                    style = MaterialTheme.typography.bodyMedium,
                    color = sem.textTertiary,
                )
                Text(
                    summary,
                    style = dev.dsh.remote.ui.theme.AppType.monoSmall(color = sem.textTertiary),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (expandable) {
                DshIcon(
                    if (expanded) DshIcons.ChevronUp else DshIcons.ChevronDown,
                    tint = sem.textTertiary,
                    size = 15.dp,
                    contentDescription = null,
                )
            }
        }
        if (expanded) {
            Column(Modifier.fillMaxWidth().padding(start = 42.dp, end = 6.dp, bottom = 6.dp)) {
                when {
                    item is ChatItem.Assistant -> Text(
                        fullReasoning(item.seq).ifBlank { item.reasoning },
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                    item is ChatItem.Tool && item.diffOld != null && item.diffNew != null -> DiffView(item)
                    item is ChatItem.Tool -> Text(
                        item.arguments,
                        style = dev.dsh.remote.ui.theme.AppType.monoSmall(color = sem.textTertiary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
        }
    }
}
@Composable
private fun ToolGroupCard(steps: List<ChatItem.Tool>, running: Boolean) {
    var expanded by remember(steps.size) { mutableStateOf(true) }
    // Auto-collapse when the turn finishes, without fighting a manual expand.
    LaunchedEffect(running) { if (!running) expanded = false }
    // The folded group takes the colour of whatever it mostly did, so a shell-heavy
    // turn and an edit-heavy turn are distinguishable at a glance.
    val dominant = remember(steps.size, steps.firstOrNull()?.seq) {
        steps.groupingBy { toolKindOf(it.name) }.eachCount().maxByOrNull { it.value }?.key ?: ToolKind.Other
    }
    val hasError = steps.any { it.isError }
    val sem = dev.dsh.remote.ui.theme.LocalDshSemantic.current
    val scheme = MaterialTheme.colorScheme
    val tone = toolToneOf(dominant)
    val (toneContainer, toneOn) = if (hasError) sem.dangerContainer to sem.onDangerContainer else sem.tool(tone)

    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(dev.dsh.remote.ui.theme.AppShape.md))
            .background(toneContainer.copy(alpha = 0.72f)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .pressableClick(onClick = { expanded = !expanded })
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (running) {
                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = toneOn)
            } else {
                DshIcon(DshIcons.Check, tint = toneOn, size = 14.dp, contentDescription = null)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                if (running) "执行中 · ${steps.size} 个步骤" else "已完成 · ${steps.size} 个步骤",
                style = MaterialTheme.typography.labelMedium,
                color = toneOn,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (expanded) "收起" else "展开",
                style = MaterialTheme.typography.labelSmall,
                color = toneOn.copy(alpha = 0.75f),
            )
            DshIcon(
                if (expanded) DshIcons.ChevronDown else DshIcons.ChevronRight,
                tint = toneOn.copy(alpha = 0.75f),
                size = 14.dp,
                contentDescription = null,
            )
        }
        if (expanded) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp)) {
                steps.forEach { step -> ToolCard(step, running = false) }
            }
        }
    }
}

/** Set by ChatScreen so tool cards can download without threading the view model through. */
private object ChatFiles {
    var download: ((String) -> Unit)? = null
    /** Asks for confirmation before downloading (long-press entry point). */
    var confirm: ((FileLink) -> Unit)? = null
    /** 复制路径到剪贴板。 */
    var copyPath: ((String) -> Unit)? = null
}

/**
 * 本轮里带 diff 的工具调用,按路径索引。
 *
 * 「查看改动」需要按文件路径找回那份 diff;文件卡本身只带路径与行数,所以由
 * ChatScreen 在渲染前把本轮的 diff 登记进来。
 */
private object ChatDiffRegistry {
    private val byPath = HashMap<String, ChatItem.Tool>()

    fun reset() = byPath.clear()

    fun register(tool: ChatItem.Tool) {
        val path = tool.diffPath?.takeIf { it.isNotBlank() } ?: return
        if (tool.diffOld == null || tool.diffNew == null) return
        byPath[path] = tool
    }

    fun find(path: String): ChatItem.Tool? = byPath[path]
}

/** One file referenced by a tool result card. */
/**
 * 一个被工具碰过的文件。
 *
 * [added] / [removed] 来自 diff(有 diff 才有真实数字),没有 diff 时是 0 ——
 * 界面据此决定"要不要显示 +N"。
 */
private data class FileLink(
    val path: String,
    val label: String,
    val added: Int = 0,
    val removed: Int = 0,
)

/**
 * Lift the `files` array out of a tool's card/arguments JSON.
 *
 * dsh renders these as rich cards; the app only receives the raw payload, so the
 * entries are extracted here and drawn as rows with a download action.
 */
private fun parseFileLinks(card: String?, arguments: String?): List<FileLink> {
    val body = card?.takeIf { it.isNotBlank() } ?: arguments?.takeIf { it.isNotBlank() } ?: return emptyList()
    return try {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(body)
        val arr = when (root) {
            is kotlinx.serialization.json.JsonArray -> root
            is kotlinx.serialization.json.JsonObject ->
                root["files"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
            else -> return emptyList()
        }
        arr.mapNotNull { el ->
            val obj = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            fun str(vararg keys: String): String? = keys.firstNotNullOfOrNull {
                (obj[it] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { v -> v.isNotBlank() }
            }
            val path = str("path", "file", "name", "url") ?: return@mapNotNull null
            FileLink(path, str("description", "desc", "label", "title") ?: path.substringAfterLast('/'))
        }
    } catch (_: Exception) {
        emptyList()
    }
}

/**
 * 文件行:图标块 + 路径(等宽,单行省略) + 右侧增量。
 *
 * 长按 = 下载(先确认)。[onClick] 为空时整行不可点。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FileLinkRow(
    f: FileLink,
    leading: Int = DshIcons.FileAdd,
    leadingTint: Color,
    showStat: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val sem = dev.dsh.remote.ui.theme.LocalDshSemantic.current
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(dev.dsh.remote.ui.theme.AppShape.sm))
            .combinedClickable(
                onClick = { onClick?.invoke() },
                onLongClick = { ChatFiles.confirm?.invoke(f) },
            )
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DshIcon(leading, tint = leadingTint, size = 17.dp, contentDescription = null)
        Spacer(Modifier.width(10.dp))
        Text(
            f.path,
            style = dev.dsh.remote.ui.theme.AppType.monoSmall(color = scheme.onSurface),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (showStat && f.added > 0) {
            Spacer(Modifier.width(8.dp))
            Text(
                "+${f.added}",
                style = dev.dsh.remote.ui.theme.AppType.monoLabel(color = sem.diffAddFg),
            )
        }
        if (showStat && f.removed > 0) {
            Spacer(Modifier.width(6.dp))
            Text(
                "−${f.removed}",
                style = dev.dsh.remote.ui.theme.AppType.monoLabel(color = sem.diffDelFg),
            )
        }
    }
}

/**
 * 一轮结束时的那两张卡。
 *
 * 视觉规则(对齐参考实现):
 * - 每张卡是**一整块**淡底圆角面板,卡与卡之间留白,不画分割线;
 * - 卡头 = 图标 + 一句话标题 + 右侧汇总;
 * - 改动卡底部有一条「查看改动 ›」,点开看每个文件的 diff;
 * - 交付卡的文件行是「图标块 + 文件名 + 说明 + ⋯」。
 */
@Composable
private fun TurnFilesCard(files: TurnFiles) {
    val sem = dev.dsh.remote.ui.theme.LocalDshSemantic.current
    val scheme = MaterialTheme.colorScheme
    var showDiff by remember(files) { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        // ---- 改动了 N 个文件 ----
        if (files.edited.isNotEmpty()) {
            val totalAdded = files.edited.sumOf { it.added }
            val totalRemoved = files.edited.sumOf { it.removed }
            dev.dsh.remote.ui.theme.GlassSurface(
                tier = dev.dsh.remote.ui.theme.GlassTier.Card,
                shape = RoundedCornerShape(dev.dsh.remote.ui.theme.AppShape.lg),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DshIcon(
                            DshIcons.Branch,
                            tint = scheme.onSurface,
                            size = 18.dp,
                            contentDescription = null,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "改动了 ${files.edited.size} 个文件",
                            style = MaterialTheme.typography.titleMedium,
                            color = scheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        if (totalAdded > 0) {
                            Text(
                                "+$totalAdded",
                                style = dev.dsh.remote.ui.theme.AppType.monoLabel(color = sem.diffAddFg),
                            )
                        }
                        if (totalRemoved > 0) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "−$totalRemoved",
                                style = dev.dsh.remote.ui.theme.AppType.monoLabel(color = sem.diffDelFg),
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    files.edited.take(MAX_CARD_ROWS).forEach { f ->
                        FileLinkRow(
                            f = f,
                            leading = DshIcons.FileAdd,
                            leadingTint = sem.diffAddFg,
                        )
                    }
                    if (files.edited.size > MAX_CARD_ROWS) {
                        Text(
                            Strings.str("more_files", files.edited.size - MAX_CARD_ROWS),
                            color = sem.textTertiary,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(start = 27.dp, top = 2.dp),
                        )
                    }
                    // 「查看改动 ›」:展开每个文件的 diff。
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(dev.dsh.remote.ui.theme.AppShape.sm))
                            .pressableClick(onClick = { showDiff = !showDiff })
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (showDiff) "收起改动" else "查看改动",
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        DshIcon(
                            if (showDiff) DshIcons.ChevronDown else DshIcons.ChevronRight,
                            tint = sem.textTertiary,
                            size = 16.dp,
                            contentDescription = null,
                        )
                    }
                }
            }
            // 展开的 diff:每个文件一段,放在卡片下面,不再塞进卡里挤掉列表。
            if (showDiff) {
                Spacer(Modifier.height(4.dp))
                files.edited.forEach { f ->
                    val tool = ChatDiffRegistry.find(f.path)
                    if (tool != null) {
                        Box(Modifier.padding(start = 4.dp, top = 2.dp)) { DiffView(tool) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // ---- 交付了 N 个文件 ----
        if (files.presented.isNotEmpty()) {
            dev.dsh.remote.ui.theme.GlassSurface(
                tier = dev.dsh.remote.ui.theme.GlassTier.Card,
                shape = RoundedCornerShape(dev.dsh.remote.ui.theme.AppShape.lg),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DshIcon(
                            DshIcons.Box,
                            tint = scheme.onSurface,
                            size = 18.dp,
                            contentDescription = null,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "交付了 ${files.presented.size} 个文件",
                            style = MaterialTheme.typography.titleMedium,
                            color = scheme.onSurface,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    files.presented.take(MAX_CARD_ROWS).forEach { f ->
                        DeliveredRow(f)
                    }
                    if (files.presented.size > MAX_CARD_ROWS) {
                        Text(
                            Strings.str("more_files", files.presented.size - MAX_CARD_ROWS),
                            color = sem.textTertiary,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 交付行:图标块 + 文件名 + 说明 + ⋯。
 *
 * 图标块按扩展名换(ZIP / APK / 图片 / 其它),说明取工具给的 label,
 * 没有就用路径。点 ⋯ 出菜单(打开 / 下载)。
 */
@Composable
private fun DeliveredRow(f: FileLink) {
    val sem = dev.dsh.remote.ui.theme.LocalDshSemantic.current
    val scheme = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    val ext = f.path.substringAfterLast('.', "").lowercase()
    val icon = when (ext) {
        "zip", "apk", "aab", "jar", "tar", "gz", "7z", "rar" -> DshIcons.FileZip
        else -> DshIcons.FolderOpen
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(dev.dsh.remote.ui.theme.AppShape.md))
            .combinedClickable(
                onClick = { ChatFiles.confirm?.invoke(f) },
                onLongClick = { ChatFiles.confirm?.invoke(f) },
            )
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 图标块:浅底 + 描边感的圆角方块,像一个小"文件类型徽章"。
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(dev.dsh.remote.ui.theme.AppShape.md))
                .background(scheme.onSurface.copy(alpha = 0.04f)),
            contentAlignment = Alignment.Center,
        ) {
            DshIcon(icon, tint = scheme.onSurfaceVariant, size = 19.dp, contentDescription = null)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                f.path.substringAfterLast('/').substringAfterLast('\\'),
                style = MaterialTheme.typography.bodyLarge,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            val sub = f.label.takeIf { it.isNotBlank() && it != f.path.substringAfterLast('/') }
            if (sub != null) {
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = sem.textTertiary,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) {
                Text(
                    "⋯",
                    style = MaterialTheme.typography.titleMedium,
                    color = sem.textTertiary,
                )
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("下载") },
                    onClick = { menu = false; ChatFiles.confirm?.invoke(f) },
                )
                DropdownMenuItem(
                    text = { Text("复制路径") },
                    onClick = {
                        menu = false
                        ChatFiles.copyPath?.invoke(f.path)
                    },
                )
            }
        }
    }
}
/** 附件的第二行说明:类型标签 · 大小,宿主没给标签就只显示大小。 */
private fun attachmentDetail(att: dev.dsh.remote.data.UploadedAttachment): String {
    val size = when {
        att.bytes >= 1024L * 1024L -> "%.1f MB".format(att.bytes / 1024.0 / 1024.0)
        att.bytes >= 1024L -> "%.0f KB".format(att.bytes / 1024.0)
        else -> "${att.bytes} B"
    }
    val label = att.label?.takeIf { it.isNotBlank() }
    return if (label != null) "$label · $size" else size
}

/**
 * 输入条上方的附件卡片。
 *
 * 图标按扩展名换(文档 / 表格 / 演示 / PDF / 图片 / 压缩包),上传中显示进度条,
 * 完成后可点右侧 × 移除。
 */
@Composable
private fun AttachmentCard(
    name: String,
    detail: String,
    progress: Float?,
    onRemove: (() -> Unit)?,
) {
    val sem = dev.dsh.remote.ui.theme.LocalDshSemantic.current
    val scheme = MaterialTheme.colorScheme
    val ext = name.substringAfterLast('.', "").lowercase()
    val icon = when (ext) {
        "pdf" -> DshIcons.FileAdd
        "doc", "docx", "rtf" -> DshIcons.Edit
        "xls", "xlsx", "csv" -> DshIcons.Checklist
        "ppt", "pptx" -> DshIcons.Data
        "zip", "7z", "rar", "tar", "gz" -> DshIcons.FileZip
        "png", "jpg", "jpeg", "webp", "gif" -> DshIcons.Browse
        else -> DshIcons.Folder
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(dev.dsh.remote.ui.theme.AppShape.md))
            .background(scheme.onSurface.copy(alpha = 0.04f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(dev.dsh.remote.ui.theme.AppShape.sm))
                .background(scheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            DshIcon(icon, tint = scheme.primary, size = 16.dp, contentDescription = null)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = sem.textTertiary,
            )
            if (progress != null) {
                Spacer(Modifier.height(4.dp))
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = scheme.primary,
                    trackColor = scheme.primary.copy(alpha = 0.15f),
                )
            }
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                DshIcon(
                    DshIcons.Close,
                    tint = sem.textTertiary,
                    size = 14.dp,
                    contentDescription = "移除附件",
                )
            }
        }
    }
}
@Composable
private fun ToolCard(item: ChatItem.Tool, running: Boolean = false) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    val isError = item.isError
    val displayName = when (item.name) {
        "tool/error" -> Strings.str("tool_exec_error")
        "tool/result" -> Strings.str("tool_result")
        else -> item.name
    }
    val summary = item.summary ?: item.arguments.lineSequence().firstOrNull { it.isNotBlank() }?.take(80) ?: ""
    val icon = toolIcon(item.card, item.name)
    val files = remember(item.seq, item.card) { parseFileLinks(item.card, item.arguments) }
    // Colour by what the tool does, using the host Web UI's own palette; errors keep
    // the red so a failure never hides behind a category colour.
    val kind = remember(item.name) { toolKindOf(item.name) }

    val tone = remember(kind) { toolToneOf(kind) }
    val sem = dev.dsh.remote.ui.theme.LocalDshSemantic.current
    val scheme = MaterialTheme.colorScheme
    val (toneContainer, toneOn) = if (isError) {
        sem.dangerContainer to sem.onDangerContainer
    } else {
        sem.tool(tone)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(dev.dsh.remote.ui.theme.AppShape.sm))
            .background(toneContainer.copy(alpha = if (isError) 0.85f else 1f))
            .pressableClick(onClick = { expanded = !expanded })
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        // File-carrying results get a real card: name, description, download.
        // The raw JSON is still reachable by expanding.
        if (files.isNotEmpty() && !expanded) {
            files.forEach { f ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .combinedClickable(onClick = {}, onLongClick = { ChatFiles.confirm?.invoke(f) })
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DshIcon(DshIcons.FolderOpen, tint = MaterialTheme.colorScheme.primary, size = 16.dp, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            f.path.substringAfterLast('/'),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        if (f.label.isNotBlank() && f.label != f.path.substringAfterLast('/')) {
                            Text(
                                f.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                    }
                    DshIcon(DshIcons.Copy, tint = MaterialTheme.colorScheme.primary, size = 16.dp, contentDescription = null)
                }
            }
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isError) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(ToolErrorColor, CircleShape),
                )
                Spacer(Modifier.width(6.dp))
            }
            val nameColor = if (isError) ToolErrorColor else kind.color
            Row(verticalAlignment = Alignment.CenterVertically) {
                DshIcon(icon, tint = nameColor, size = 14.dp)
                Spacer(Modifier.width(4.dp))
                Text(
                    displayName,
                    color = nameColor,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodySmall,
                )
                // A tool that has not produced its result yet is still executing.
                if (running && !item.isResult) {
                    Spacer(Modifier.width(6.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(11.dp),
                        strokeWidth = 1.5.dp,
                        color = nameColor,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            if (item.arguments.isNotBlank()) {
                IconButton(onClick = {
                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("tool", item.arguments))
                    android.widget.Toast.makeText(context, Strings.str("copied"), android.widget.Toast.LENGTH_SHORT).show()
                }, modifier = Modifier.size(28.dp)) {
                    DshIcon(DshIcons.Copy, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 14.dp, contentDescription = Strings.str("copy"))
                }
            }
            Text(
                if (expanded) Strings.str("collapse") else Strings.str("expand"),
                color = sem.textTertiary,
                style = MaterialTheme.typography.labelSmall,
            )
            DshIcon(
                if (expanded) DshIcons.ChevronUp else DshIcons.ChevronDown,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                size = 12.dp,
            )
        }
        if (!expanded && summary.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                summary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        if (expanded) {
            Spacer(Modifier.height(4.dp))
            if (item.diffOld != null && item.diffNew != null) {
                DiffView(item)
            } else if (item.arguments.isNotBlank()) {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        item.arguments,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

private sealed class DiffLine {
    data class Context(val text: String) : DiffLine()
    data class Removed(val text: String) : DiffLine()
    data class Added(val text: String) : DiffLine()
}

private fun computeDiff(old: String, new: String): List<DiffLine> {
    val o = old.lines()
    val n = new.lines()
    var prefix = 0
    while (prefix < o.size && prefix < n.size && o[prefix] == n[prefix]) prefix++
    var suffix = 0
    while (suffix < o.size - prefix && suffix < n.size - prefix &&
        o[o.size - 1 - suffix] == n[n.size - 1 - suffix]) suffix++
    val out = ArrayList<DiffLine>()
    for (i in 0 until prefix) out.add(DiffLine.Context(o[i]))
    for (i in prefix until o.size - suffix) out.add(DiffLine.Removed(o[i]))
    for (i in prefix until n.size - suffix) out.add(DiffLine.Added(n[i]))
    for (i in o.size - suffix until o.size) out.add(DiffLine.Context(o[i]))
    return out
}

@Composable
private fun DiffView(item: ChatItem.Tool) {
    val lines = remember(item.diffOld, item.diffNew) { computeDiff(item.diffOld ?: "", item.diffNew ?: "") }
    val added = lines.count { it is DiffLine.Added }
    val removed = lines.count { it is DiffLine.Removed }
    Column(Modifier.fillMaxWidth()) {
        if (item.diffPath != null) {
            Text(
                item.diffPath,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
        }
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState())
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
                .padding(6.dp),
        ) {
            lines.forEach { line ->
                val (prefix, color, text) = when (line) {
                    is DiffLine.Added -> Triple("+ ", DshGreen, line.text)
                    is DiffLine.Removed -> Triple("- ", DshRed, line.text)
                    is DiffLine.Context -> Triple("  ", MaterialTheme.colorScheme.onSurfaceVariant, line.text)
                }
                Text(
                    prefix + text,
                    color = color,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            "└ +$added -$removed · 1 file",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/** Whale-tail mark from the Web chat's running indicator (16x16 viewBox), gently swaying. */
@Composable
private fun WhaleTail(modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color) {
    val transition = rememberInfiniteTransition(label = "whale")
    val angle by transition.animateFloat(
        initialValue = -9f,
        targetValue = 9f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "sway",
    )
    Canvas(modifier) {
        val s = size.minDimension / 16f
        val tail = Path().apply {
            moveTo(8.844f * s, 13.742f * s)
            cubicTo(8.967f * s, 12.328f * s, 8.45f * s, 10.4f * s, 8.45f * s, 9.65f * s)
            cubicTo(8.45f * s, 8.94f * s, 8.88f * s, 8.43f * s, 9.6f * s, 8.43f * s)
            cubicTo(11.285f * s, 8.43f * s, 12.106f * s, 8.281f * s, 12.685f * s, 8.104f * s)
            cubicTo(13.71f * s, 7.791f * s, 14.585f * s, 6.768f * s, 15.055f * s, 5.945f * s)
            cubicTo(15.137f * s, 5.803f * s, 14.99f * s, 5.641f * s, 14.829f * s, 5.671f * s)
            cubicTo(13.829f * s, 5.86f * s, 12.828f * s, 5.376f * s, 11.827f * s, 4.978f * s)
            cubicTo(10.659f * s, 4.514f * s, 9.491f * s, 4.707f * s, 8.935f * s, 4.876f * s)
            cubicTo(8.805f * s, 4.915f * s, 8.658f * s, 4.819f * s, 8.636f * s, 4.686f * s)
            cubicTo(8.468f * s, 3.643f * s, 7.405f * s, 2.615f * s, 5.498f * s, 2.238f * s)
            cubicTo(4.54f * s, 2.048f * s, 3.748f * s, 1.574f * s, 3.347f * s, 1.202f * s)
            cubicTo(3.252f * s, 1.113f * s, 3.088f * s, 1.125f * s, 3.03f * s, 1.242f * s)
            cubicTo(2.628f * s, 2.059f * s, 2.168f * s, 3.82f * s, 5.248f * s, 6.115f * s)
            cubicTo(5.82f * s, 6.494f * s, 6.31f * s, 6.785f * s, 6.574f * s, 7.637f * s)
            cubicTo(6.72f * s, 8.104f * s, 6.157f * s, 9.168f * s, 6.061f * s, 9.368f * s)
            cubicTo(5.157f * s, 11.27f * s, 5.089f * s, 12.19f * s, 4.926f * s, 13.742f * s)
        }
        rotate(angle, pivot = Offset(size.width / 2f, size.height / 2f)) {
            drawPath(tail, color, style = Stroke(width = 1.2f * s, cap = StrokeCap.Round))
        }
    }
}

/** Whale-style running indicator: spinner plus a live "deep diving for mm:ss" label. */
/**
 * Tokens/sec · context usage · cache hit rate, permanently visible.
 *
 * tokens/sec comes from this turn's output tokens over the observed run time, the
 * context figure from the host's `contextPressure` projection, and the hit rate from
 * cache-read tokens over everything the model read.
 */
@Composable
private fun StatsStrip(vm: AppViewModel) {
    val usage by vm.turnUsageFlow.collectAsState()
    val startedAt by vm.runStartedAt.collectAsState()
    val pressure by vm.currentContextPressure.collectAsState()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) {
        while (true) {
            kotlinx.coroutines.delay(1_000L)
            now = System.currentTimeMillis()
        }
    }

    val u = usage ?: return
    val elapsedMs = startedAt?.let { (now - it).coerceAtLeast(1L) } ?: 0L
    val tps = if (elapsedMs > 0 && u.outputTokens > 0) u.outputTokens * 1000.0 / elapsedMs else 0.0
    val ctxPct = pressure?.let { p ->
        if (p.contextWindow > 0) {
            p.projectedTokens.coerceAtLeast(p.pressureTokens) * 100.0 / p.contextWindow
        } else null
    }
    val hitPct = if (u.totalInput > 0) u.cacheReadTokens * 100.0 / u.totalInput else null

    val sem = dev.dsh.remote.ui.theme.LocalDshSemantic.current
    val scheme = MaterialTheme.colorScheme
    // 一行细小的等宽指标:等宽字体 + 浅色,数字不跳动也不抢眼。
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (tps > 0.05) "%.1f tok/s".format(tps) else "— tok/s",
            style = dev.dsh.remote.ui.theme.AppType.monoLabel(color = sem.textTertiary),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            ctxPct?.let { "ctx %.0f%%".format(it) } ?: "ctx —",
            style = dev.dsh.remote.ui.theme.AppType.monoLabel(
                color = if ((ctxPct ?: 0.0) > 85.0) sem.danger else sem.textTertiary,
            ),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            hitPct?.let { "cache %.0f%%".format(it) } ?: "cache —",
            style = dev.dsh.remote.ui.theme.AppType.monoLabel(color = sem.textTertiary),
        )
    }
}

@Composable
private fun RunStatusBar(vm: AppViewModel) {
    val startedAt by vm.runStartedAt.collectAsState()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) {
        if (startedAt == null) return@LaunchedEffect
        now = System.currentTimeMillis()
        while (true) {
            kotlinx.coroutines.delay(1000L)
            now = System.currentTimeMillis()
        }
    }
    val seconds = startedAt?.let { ((now - it) / 1000L).coerceAtLeast(0L) } ?: 0L
    val label = if (startedAt == null) {
        Strings.str("deep_diving")
    } else {
        Strings.str("deep_diving_for", formatRunDuration(seconds))
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WhaleTail(Modifier.size(16.dp), MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Mirror the Web chat's run-duration formatting: 42s / 3m 07s / 1h 05m. */
private fun formatRunDuration(totalSeconds: Long): String {
    if (totalSeconds < 60) return "${totalSeconds}s"
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    if (minutes < 60) return "${minutes}m ${seconds.toString().padStart(2, '0')}s"
    val hours = minutes / 60
    return "${hours}h ${(minutes % 60).toString().padStart(2, '0')}m"
}

@Composable
private fun Composer(vm: AppViewModel, currentModel: String, running: Boolean) {
    val draft by vm.draft.collectAsState()
    val sem = dev.dsh.remote.ui.theme.LocalDshSemantic.current
    val scheme = MaterialTheme.colorScheme
    val queue by vm.queueItems.collectAsState()
    var modelMenu by remember { mutableStateOf(false) }
    var modelPicker by remember { mutableStateOf(false) }
    val models by vm.models.collectAsState()
    // The catalog may not have arrived when the composer first shows; retry every
    // time the menu opens so a tap always has something to list.
    LaunchedEffect(modelMenu) { if (modelMenu) vm.ensureModels() }
    val agentPresets by vm.agentPresets.collectAsState()
    var presetMenu by remember { mutableStateOf(false) }
    var effortMenu by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var editText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.loadAgentPresets() }

    val currentEfforts = models?.groups?.flatMap { it.models }
        ?.firstOrNull { it.id == models?.current?.model }?.reasoning?.efforts.orEmpty()

    val sessions by vm.sessions.collectAsState()
    val currentPreset = sessions.firstOrNull { it.sessionId == vm.currentSessionId.value }?.agentPreset
    val currentEffort = models?.current?.reasoningEffort

    val context = LocalContext.current
    val pickScope = rememberCoroutineScope()
    // 文档 / 表格 / 演示附件:走 dsh-file-upload 的 /api/upload(原始字节)。
    val attachments by vm.attachments.collectAsState()
    val uploading by vm.uploading.collectAsState()
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        uris.forEach { uri -> vm.uploadAttachment(uri) }
    }
    var pendingImages by remember { mutableStateOf<List<PendingImage>>(emptyList()) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            pickScope.launch {
                try {
                    val loaded = uris.mapNotNull { uri ->
                        try {
                            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@mapNotNull null
                            val mediaType = context.contentResolver.getType(uri) ?: "image/png"
                            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                            val bitmap = runCatching { decodeThumbnail(bytes) }.getOrNull()
                            PendingImage(mediaType, base64, uri.lastPathSegment, bitmap)
                        } catch (_: Exception) { null }
                    }
                    pendingImages = pendingImages + loaded
                } catch (_: Exception) {}
            }
        }
    }

    val surfaces = dev.dsh.remote.ui.theme.LocalDshSurfaces.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
        ModeRow(vm)
        Spacer(Modifier.height(6.dp))
        // 输入条:浮在内容上的玻璃(取样背景),不是一块不透明面板。
        dev.dsh.remote.ui.theme.GlassSurface(
            tier = dev.dsh.remote.ui.theme.GlassTier.Chrome,
            shape = RoundedCornerShape(dev.dsh.remote.ui.theme.AppShape.xl),
            modifier = Modifier.fillMaxWidth(),
        ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box {
                Row(
                    Modifier
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                        .clickable { modelMenu = true }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(currentModel, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    DshIcon(DshIcons.ChevronDown, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 14.dp)
                }
                DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                    // A menu cannot hold the whole catalogue (plugins push it into the
                    // thousands of entries) and silently truncates: hand off to a
                    // scrollable, searchable picker instead.
                    DropdownMenuItem(
                        text = { Text("选择模型…（${models?.groups?.sumOf { it.models.size } ?: 0} 个）") },
                        onClick = { modelMenu = false; modelPicker = true },
                    )
                }
                if (modelPicker) {
                    ModelPickerDialog(
                        groups = models?.groups.orEmpty(),
                        onDismiss = { modelPicker = false },
                        onPick = { gid, mid, effort ->
                            vm.currentSessionId.value?.let { vm.selectModel(it, gid, mid, effort) }
                            modelPicker = false
                        },
                    )
                }
            }
            if (agentPresets.isNotEmpty()) {
                Box {
                    Row(
                        Modifier
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                            .clickable { presetMenu = true }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(currentPreset ?: Strings.str("preset"), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        DshIcon(DshIcons.ChevronDown, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 14.dp)
                    }
                    DropdownMenu(expanded = presetMenu, onDismissRequest = { presetMenu = false }) {
                        for (p in agentPresets) {
                            DropdownMenuItem(
                                text = { Text(p.name ?: p.id) },
                                onClick = { vm.selectAgentPreset(p.id); presetMenu = false },
                            )
                        }
                    }
                }
            }
            if (currentEfforts.isNotEmpty()) {
                Box {
                    Row(
                        Modifier
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                            .clickable { effortMenu = true }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(currentEffort ?: Strings.str("reasoning_effort"), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        DshIcon(DshIcons.ChevronDown, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 14.dp)
                    }
                    DropdownMenu(expanded = effortMenu, onDismissRequest = { effortMenu = false }) {
                        for (e in currentEfforts) {
                            DropdownMenuItem(
                                text = { Text(e.name) },
                                onClick = { vm.selectReasoningEffort(e.id); effortMenu = false },
                            )
                        }
                    }
                }
            }
        }
        if (queue.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DshIcon(DshIcons.Queue, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 14.dp)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        Strings.str("msg_queue", queue.size),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                queue.forEachIndexed { i, item ->
                    val msg = textOf(item.message.content)
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${i + 1}. ${msg.take(60)}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { editingId = item.id; editText = msg },
                        )
                        DshIcon(
                            DshIcons.CloseFill,
                            tint = DshRed,
                            size = 14.dp,
                            modifier = Modifier
                                .clickable { vm.removeQueued(item.id) }
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
        // ---- 文档附件卡片 ----
        if (attachments.isNotEmpty() || uploading.isNotEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                attachments.forEach { att ->
                    AttachmentCard(
                        name = att.name,
                        detail = attachmentDetail(att),
                        progress = null,
                        onRemove = { vm.removeAttachment(att.name) },
                    )
                }
                uploading.forEach { (name, progress) ->
                    AttachmentCard(
                        name = name,
                        detail = "上传中 ${(progress * 100).toInt()}%",
                        progress = progress,
                        onRemove = null,
                    )
                }
            }
        }

        if (pendingImages.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    pendingImages.forEachIndexed { index, img ->
                        Box {
                            img.bitmap?.let { bmp ->
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = Strings.str("attachment"),
                                    modifier = Modifier
                                        .size(56.dp)
                                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
                                )
                            }
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .size(18.dp)
                                    .background(DshRed, CircleShape)
                                    .clickable { pendingImages = pendingImages.filterIndexed { i, _ -> i != index } },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("×", color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    if (pendingImages.size == 1) {
                        val img = pendingImages[0]
                        Text(
                            img.name ?: Strings.str("image_attachment"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    } else {
                        Text(
                            "${pendingImages.size} ${Strings.str("attachments")}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            // 柔和圆角:去掉 OutlinedTextField 的硬描边,改成一整块淡底 + 大圆角。
            // 描边会把输入区框成"表单控件",而这里它应该像一张纸。
            TextField(
                value = draft,
                onValueChange = { vm.setDraft(it) },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        Strings.str("input_hint"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = sem.textTertiary,
                    )
                },
                maxLines = 6,
                shape = RoundedCornerShape(dev.dsh.remote.ui.theme.AppShape.lg),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = scheme.onSurface.copy(alpha = 0.04f),
                    unfocusedContainerColor = scheme.onSurface.copy(alpha = 0.04f),
                    disabledContainerColor = scheme.onSurface.copy(alpha = 0.03f),
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    cursorColor = scheme.primary,
                ),
                visualTransformation = SlashCommandTransformation(DshAmber),
            )
            if (running) {
                IconButton(
                    onClick = { vm.cancel() },
                    modifier = Modifier.size(48.dp),
                ) {
                    DshIcon(
                        DshIcons.Stop,
                        tint = DshRed,
                        size = 20.dp,
                        contentDescription = Strings.str("stop"),
                    )
                }
            }
            // 回形针:文档 / 表格 / 演示 / 图片都能选(以前只给图片)。
            IconButton(
                onClick = {
                    filePicker.launch(
                        arrayOf(
                            "application/pdf",
                            "application/msword",
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                            "application/vnd.ms-excel",
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                            "application/vnd.ms-powerpoint",
                            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                            "text/*",
                            "application/json",
                            "application/rtf",
                            "application/epub+zip",
                            "application/zip",
                            "image/*",
                        ),
                    )
                },
                modifier = Modifier.size(48.dp),
            ) {
                DshIcon(DshIcons.Paperclip, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 20.dp)
            }
            IconButton(
                onClick = {
                    val imgs = pendingImages
                    val sessionId = vm.currentSessionId.value
                    if (imgs.isNotEmpty() && sessionId != null) {
                        val data = imgs.map { ImageData(it.mediaType, it.base64, it.name) }
                        vm.sendImagesText(sessionId, data, draft)
                        pendingImages = emptyList()
                    } else if (running) {
                        vm.enqueue(draft)
                    } else {
                        val sid = vm.currentSessionId.value
                        if (attachments.isNotEmpty() && sid != null) {
                            vm.sendWithAttachments(sid, draft)
                        } else {
                            vm.send(draft)
                        }
                    }
                },
                enabled = draft.isNotBlank() || pendingImages.isNotEmpty() || attachments.isNotEmpty(),
                modifier = Modifier.size(48.dp),
            ) {
                DshIcon(
                    DshIcons.Send,
                    tint = MaterialTheme.colorScheme.primary,
                    size = 20.dp,
                    contentDescription = if (running) Strings.str("enqueue") else Strings.str("send"),
                )
            }
        }
        }
        }
    }

    editingId?.let { id ->
        AlertDialog(
            onDismissRequest = { editingId = null },
            title = { Text(Strings.str("edit_queue_msg")) },
            text = {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.editQueued(id, editText); editingId = null }) { Text(Strings.str("ok")) }
            },
            dismissButton = {
                TextButton(onClick = { editingId = null }) { Text(Strings.str("cancel")) }
            },
        )
    }
}
