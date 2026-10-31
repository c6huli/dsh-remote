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
import dev.dsh.remote.ui.theme.DshAmber
import dev.dsh.remote.ui.theme.DshGreen
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
        val rows = remember(chatItems) { groupToolSteps(chatItems) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
            ) {
                if (loadingOlder) {
                    item(key = "loading-older") {
                        Text(Strings.str("load_earlier"), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
                items(rows) { row ->
                    when (row) {
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
                IconButton(
                    onClick = { scope.launch { listState.scrollToBottom() } },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                        .size(40.dp),
                ) {
                    DshIcon(
                        DshIcons.ChevronDown,
                        tint = MaterialTheme.colorScheme.primary,
                        size = 20.dp,
                        contentDescription = Strings.str("jump_bottom"),
                    )
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
    Column(
        Modifier
            .fillMaxWidth()
            .clickable {
                expanded = !expanded
                if (expanded) {
                    if (fullText == null) fullText = fullProvider()
                    onExpanded()
                }
            }
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DshIcon(DshIcons.Think, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 14.dp)
            Spacer(Modifier.width(4.dp))
            Text(
                Strings.str("reasoning"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (expanded) Strings.str("collapse") else Strings.str("expand"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Box(
                    Modifier
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .widthIn(max = 320.dp),
                ) {
                    Column {
                        if (item.text.isNotBlank()) {
                            MarkdownText(item.text, color = DshUserText)
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
        is ChatItem.Cost -> {
            Text(
                item.text,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            )
        }
        is ChatItem.Deliverables -> {
            DeliverablesRow(item.paths)
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
}

/** Rows shown in a turn's file card before collapsing the rest into a `+N` hint. */
private const val MAX_CARD_ROWS = 8

/**
 * Fold every run of consecutive tool steps into a single row.
 *
 * A single step stays on its own (there is nothing to fold), which keeps simple
 * answers looking exactly as before.
 */
private fun groupToolSteps(items: List<ChatItem>): List<ChatRow> {
    val out = ArrayList<ChatRow>(items.size)
    var buf = ArrayList<ChatItem.Tool>()
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
    fun flush() {
        if (buf.size == 1) out.add(ChatRow.Single(buf[0])) else if (buf.size > 1) out.add(ChatRow.Tools(buf.toList()))
        buf = ArrayList()
    }
    fun flushFiles() {
        val files = TurnFiles(presented.toList(), edited.toList())
        if (!files.isEmpty) out.add(ChatRow.Files(files))
        presented = ArrayList()
        edited = ArrayList()
        turnSeen = HashSet()
    }
    for (item in items) {
        if (item is ChatItem.Tool) {
            buf.add(item)
            val (delivered, touched) = filesOfTool(item)
            for (f in touched) if (turnSeen.add(f.path)) edited.add(f)
            // A handed-over file wins over a later "edited" entry for the same path.
            for (f in delivered) {
                if (turnSeen.add(f.path)) presented.add(f) else {
                    edited.removeAll { it.path == f.path }
                    if (presented.none { it.path == f.path }) presented.add(f)
                }
            }
        } else {
            flush()
            out.add(ChatRow.Single(item))
            // A turn boundary: emit that turn's files right after its last output.
            // A user message is a hard boundary too, in case a turn never ended
            // cleanly (aborted run) — otherwise its files would leak into the next.
            if ((item is ChatItem.Assistant && item.isTurnEnd) || item is ChatItem.User) flushFiles()
        }
    }
    flush()
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
    if (isPresent) delivered.addAll(parseFileLinks(t.card, t.arguments)) else edited.addAll(parseFileLinks(t.card, t.arguments))
    t.diffPath?.takeIf { it.isNotBlank() }?.let { edited.add(FileLink(it, labelOf(it))) }
    if (t.name.lowercase() in FILE_WRITING_TOOLS && !isPresent) {
        pathOfToolArguments(t.arguments)?.let { edited.add(FileLink(it, labelOf(it))) }
    }
    return delivered to edited
}

private fun labelOf(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')

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
    val accent = if (hasError) ToolErrorColor else dominant.color

    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(accent.copy(alpha = 0.10f), RoundedCornerShape(8.dp)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (running) {
                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = accent)
            } else {
                DshIcon(DshIcons.Check, tint = accent, size = 14.dp, contentDescription = null)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                if (running) "执行中 · ${steps.size} 个步骤" else "已完成 · ${steps.size} 个步骤",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (expanded) "收起" else "展开",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            DshIcon(
                if (expanded) DshIcons.ChevronDown else DshIcons.ChevronRight,
                tint = MaterialTheme.colorScheme.primary,
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
}

/** One file referenced by a tool result card. */
private data class FileLink(val path: String, val label: String)

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

/** One downloadable file row, shared by single tool cards and folded groups. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FileLinkRow(f: FileLink) {
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

/**
 * One turn's file card: 最终产物 on top, 已编辑文件 folded underneath.
 *
 * Splitting them keeps the delivered artefacts — the thing worth downloading —
 * at eye level, while a turn that rewrote a dozen files does not push the rest of
 * the conversation off screen. Edited rows stay one tap away and keep the same
 * long-press-to-download action.
 */
@Composable
private fun TurnFilesCard(files: TurnFiles) {
    var showEdited by remember(files) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        if (files.presented.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(ToolKind.Edit.color, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(
                    Strings.str("final_files"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(2.dp))
            files.presented.take(MAX_CARD_ROWS).forEach { f -> FileLinkRow(f) }
            if (files.presented.size > MAX_CARD_ROWS) {
                Text(
                    Strings.str("more_files", files.presented.size - MAX_CARD_ROWS),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        if (files.edited.isNotEmpty()) {
            if (files.presented.isNotEmpty()) Spacer(Modifier.height(4.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { showEdited = !showEdited }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(6.dp).background(ToolKind.Read.color, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(
                    "${Strings.str("edited_files")} · ${files.edited.size}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                DshIcon(
                    if (showEdited) DshIcons.ChevronDown else DshIcons.ChevronRight,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    size = 14.dp,
                    contentDescription = null,
                )
            }
            if (showEdited) {
                Spacer(Modifier.height(2.dp))
                files.edited.take(MAX_CARD_ROWS).forEach { f -> FileLinkRow(f) }
                if (files.edited.size > MAX_CARD_ROWS) {
                    Text(
                        Strings.str("more_files", files.edited.size - MAX_CARD_ROWS),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
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

    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clickable { expanded = !expanded }
            .background(
                when {
                    isError -> ToolErrorColor.copy(alpha = 0.12f)
                    else -> kind.tint.copy(alpha = if (item.isResult) 0.12f else 0.18f)
                },
                RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (tps > 0.05) "%.1f tok/s".format(tps) else "— tok/s",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            ctxPct?.let { "上下文 %.0f%%".format(it) } ?: "上下文 —",
            style = MaterialTheme.typography.labelSmall,
            color = if ((ctxPct ?: 0.0) > 85.0) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            hitPct?.let { "缓存 %.0f%%".format(it) } ?: "缓存 —",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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

    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(8.dp),
    ) {
        ModeRow(vm)
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { vm.setDraft(it) },
                modifier = Modifier.weight(1f),
                placeholder = { Text(Strings.str("input_hint")) },
                maxLines = 5,
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
            IconButton(
                onClick = { imagePicker.launch("*/*") },
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
                    } else if (running) vm.enqueue(draft) else vm.send(draft)
                },
                enabled = draft.isNotBlank() || pendingImages.isNotEmpty(),
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
