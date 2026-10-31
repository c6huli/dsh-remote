package dev.dsh.remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dsh.remote.data.GoalInfo
import dev.dsh.remote.data.JobView
import dev.dsh.remote.ui.icons.DshIcon
import dev.dsh.remote.ui.icons.DshIcons
import dev.dsh.remote.ui.theme.DshAmber
import dev.dsh.remote.ui.theme.DshGreen
import dev.dsh.remote.ui.theme.DshRed

/**
 * Right side rail: session facts on top, then the goal / todo / job cards, then
 * shortcuts. It overlays the conversation instead of squeezing it, matching the
 * desktop behaviour.
 */
@Composable
fun RightPanel(
    vm: AppViewModel,
    modifier: Modifier = Modifier,
    onClose: () -> Unit = {},
    onOpenFiles: () -> Unit = {},
    onOpenSubagents: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onChanges: (String, List<String>) -> Unit = { _, _ -> },
) {
    val goal by vm.currentGoal.collectAsState()
    val jobs by vm.jobs.collectAsState()
    val todos by vm.currentTodos.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val currentId by vm.currentSessionId.collectAsState()
    val models by vm.models.collectAsState()
    val running by vm.running.collectAsState()
    val runStartedAt by vm.runStartedAt.collectAsState()
    val subagents by vm.subagents.collectAsState()
    val goalInfo = goal?.goal
    val hasGoal = goalInfo != null && goalInfo.objective.isNotBlank()
    val session = sessions.firstOrNull { it.sessionId == currentId }

    Column(
        modifier
            .fillMaxHeight()
            .width(320.dp)
            .shadow(16.dp, RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
            .background(
                MaterialTheme.colorScheme.surface,
                RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp),
            )
            .border(
                1.dp,
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp),
            )
            .padding(10.dp),
    ) {
        // ---- header ----
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "侧边栏",
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClose) { Text("关闭") }
        }

        // ---- goal / todo / jobs: only when the session actually has them ----
        // Capped so a long todo list can never squeeze the workbench to nothing.
        Column(
            Modifier
                .heightIn(max = 240.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            if (hasGoal) GoalCard(vm, goalInfo!!)
            if (todos.isNotEmpty()) {
                if (hasGoal) Spacer(Modifier.height(8.dp))
                TodoCard(todos)
            }
            if (jobs.isNotEmpty()) {
                if (hasGoal || todos.isNotEmpty()) Spacer(Modifier.height(8.dp))
                JobsRow(jobs)
            }
        }
        if (hasGoal || todos.isNotEmpty() || jobs.isNotEmpty()) Spacer(Modifier.height(8.dp))

        // ---- the real dsh-better-sidebar workbench ----
        // 文件 / 文件变动 / 任务管理 / 侧边对话 / 终端 is a Web plugin, so it is rendered
        // from the host's own page and then reduced to just that panel instead of
        // being re-implemented (and drifting) here.
        SidebarEmbed(vm, Modifier.weight(1f).fillMaxWidth(), onChanges = onChanges)

        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PanelAction("文件浏览", DshIcons.FolderOpen, onOpenFiles, Modifier.weight(1f))
            PanelAction("设置", DshIcons.Settings, onOpenSettings, Modifier.weight(1f))
        }
    }
}

/**
 * The host's workbench panel, embedded and isolated.
 *
 * It only exists inside the Web app, so the page is loaded from the host (directly,
 * not through the local proxy — its websocket streams have to work) and then cut
 * down to `nArs4W_workbench`: everything off the panel's ancestry chain is hidden,
 * the chain is stretched to the viewport, and the 文件 pane is opened so the panel
 * shows its tree instead of the "pick a pane" cards.
 */
private const val SIDEBAR_PLUGIN = "dsh-better-sidebar"
private const val SIDEBAR_REPO = "https://github.com/omdsh-dev/DSH-better-sidebar"

/** Shown when the host's page has no workbench: the plugin is not installed. */
@Composable
private fun PluginMissingBlock(compact: Boolean = false) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Text(
            "未安装插件 $SIDEBAR_PLUGIN",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (!compact) {
            Spacer(Modifier.height(4.dp))
            Text(
                "侧边栏 / 文件变动面板由该插件提供，装好后这里会自动变成面板。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "GitHub：" + SIDEBAR_REPO,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable {
                try {
                    ctx.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(SIDEBAR_REPO),
                        )
                    )
                } catch (_: Throwable) {}
            },
        )
        if (!compact) {
            Spacer(Modifier.height(4.dp))
            Text(
                "安装：dsh plugin install $SIDEBAR_PLUGIN",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One panel of the host's workbench, cropped out and shown on its own.
 *
 * Same idea as the settings screen: load the page, keep only the requested block,
 * stretch it to the view bounds. Used by the chat's end-of-turn card so it shows the
 * real 文件变动 content instead of a re-rendered summary.
 */
@Composable
fun IsolatedPane(vm: AppViewModel, pane: String, modifier: Modifier = Modifier) {
    var missing by remember { mutableStateOf(false) }
    var installed by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) { installed = vm.hasBetterSidebar() }
    val serverUrl by vm.serverUrl.collectAsState()
    val activeBaseUrl by vm.activeBaseUrl.collectAsState()
    val base = (activeBaseUrl ?: serverUrl).ifBlank { "http://127.0.0.1:8080" }.trimEnd('/')
    // Loopback tunnel so dsh treats this panel as the local authority; memoised
    // because binding the listener is side-effecting.
    val entryUrl = remember(activeBaseUrl, serverUrl) { vm.webEntryUrl() }

    if (missing || installed == false) {
        androidx.compose.foundation.layout.Box(modifier.padding(8.dp)) { PluginMissingBlock(compact = true) }
        return
    }

    androidx.compose.ui.viewinterop.AndroidView(
        factory = { ctx ->
            try {
                android.webkit.WebView(ctx).apply {
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    try {
                        addJavascriptInterface(
                            PanelReadyBridge({}, { _, _ -> }, { missing = true }), "DshPanel"
                        )
                    } catch (_: Throwable) {}
                    with(settings) {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        useWideViewPort = false
                        loadWithOverviewMode = false
                        setSupportZoom(false)
                        textZoom = 100
                        cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                    }
                    webViewClient = object : android.webkit.WebViewClient() {
                        override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                            try {
                                // No harvesting here: this view *is* the pane.
                                view?.evaluateJavascript("window.__dshNoHarvest = true;", null)
                                view?.evaluateJavascript(SIDEBAR_JS, null)
                                view?.postDelayed({
                                    try {
                                        view.evaluateJavascript(
                                            "window.__dshOpenPane && window.__dshOpenPane('$pane')", null
                                        )
                                    } catch (_: Throwable) {}
                                }, 1_500L)
                            } catch (_: Throwable) {}
                        }
                    }
                    try { loadUrl(entryUrl) } catch (_: Throwable) {}
                }
            } catch (e: Throwable) {
                android.widget.TextView(ctx).apply {
                    text = "系统 WebView 不可用（${e.javaClass.simpleName}）"
                    setPadding(16, 12, 16, 12)
                }
            }
        },
        modifier = modifier,
        onRelease = { v -> try { (v as? android.webkit.WebView)?.destroy() } catch (_: Throwable) {} },
    )
}

/**
 * Signals that the embedded workbench finished cutting the page down to its panel.
 *
 * Without it the panel area is simply blank for as long as the host page takes to
 * boot (seconds on a phone), which reads as a broken screen. `changes` carries the
 * 文件变动 summary out of the page so the chat footer can show it without opening
 * the panel.
 */
private class PanelReadyBridge(
    private val onReady: () -> Unit,
    private val onChanges: (String, List<String>) -> Unit,
    private val onMissing: () -> Unit = {},
) {
    @android.webkit.JavascriptInterface
    fun ready() {
        android.os.Handler(android.os.Looper.getMainLooper()).post { onReady() }
    }

    /** The workbench never appeared: dsh-better-sidebar is not installed. */
    @android.webkit.JavascriptInterface
    fun missing() {
        android.os.Handler(android.os.Looper.getMainLooper()).post { onMissing() }
    }

    @android.webkit.JavascriptInterface
    fun changes(payload: String) {
        val cut = payload.indexOf('\u0002')
        val summary = if (cut >= 0) payload.substring(0, cut) else payload
        val files = if (cut >= 0) {
            payload.substring(cut + 1).split('\u0001').filter { it.isNotBlank() }
        } else emptyList()
        android.os.Handler(android.os.Looper.getMainLooper()).post { onChanges(summary, files) }
    }
}

@Composable
private fun SidebarEmbed(
    vm: AppViewModel,
    modifier: Modifier = Modifier,
    onChanges: (String, List<String>) -> Unit = { _, _ -> },
) {
    val serverUrl by vm.serverUrl.collectAsState()
    val activeBaseUrl by vm.activeBaseUrl.collectAsState()
    val base = (activeBaseUrl ?: serverUrl).ifBlank { "http://127.0.0.1:8080" }.trimEnd('/')
    // Loopback tunnel so dsh treats this panel as the local authority; memoised
    // because binding the listener is side-effecting.
    val entryUrl = remember(activeBaseUrl, serverUrl) { vm.webEntryUrl() }
    var ready by remember { mutableStateOf(false) }
    var missing by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var installed by remember { mutableStateOf<Boolean?>(null) }
    var webView by remember { mutableStateOf<android.webkit.WebView?>(null) }
    var activePane by remember { mutableStateOf<String?>(null) }

    // Ask the host first: no point booting a 474 KB page just to learn it has no panel.
    LaunchedEffect(Unit) { installed = vm.hasBetterSidebar() }

    // installed == null means the host check is still running: do not boot the page
    // yet, or a missing plugin costs a full render before being torn down.
    if (installed == null) {
        Box(modifier) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp).align(Alignment.Center), strokeWidth = 2.dp,
            )
        }
        return
    }
    if (installed == false || missing) {
        Box(modifier.padding(2.dp)) { PluginMissingBlock() }
        return
    }
    Column(modifier) {
        // Native pane switcher: the plugin's own tab strip inside the embedded page
        // is small and its popups are unreliable in this cut-down view, so switching
        // is driven from here via `window.__dshOpenPane`.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf("文件", "文件变动", "任务管理").forEach { name ->
                val selected = activePane == name
                Text(
                    name,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            RoundedCornerShape(12.dp),
                        )
                        .clickable {
                            activePane = name
                            try {
                                webView?.evaluateJavascript(
                                    "window.__dshOpenPane && window.__dshOpenPane('$name')", null
                                )
                            } catch (_: Throwable) {}
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))

        Box(Modifier.weight(1f).fillMaxWidth()) {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { ctx ->
                    try {
                        android.webkit.WebView(ctx).apply {
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            with(settings) {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                useWideViewPort = false
                                loadWithOverviewMode = false
                                setSupportZoom(false)
                                textZoom = 100
                                cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                            }
                            try { addJavascriptInterface(PanelReadyBridge({ ready = true }, onChanges, { missing = true }), "DshPanel") } catch (_: Throwable) {}
                            webViewClient = object : android.webkit.WebViewClient() {
                                override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                                    loadError = null
                                    try { view?.evaluateJavascript(SIDEBAR_JS, null) } catch (_: Throwable) {}
                                }

                                // A dead host used to leave this pane on a spinner
                                // forever, which reads as "cannot connect" with no
                                // way out. Surface it and offer a retry instead.
                                override fun onReceivedError(
                                    view: android.webkit.WebView?,
                                    request: android.webkit.WebResourceRequest?,
                                    error: android.webkit.WebResourceError?,
                                ) {
                                    if (request?.isForMainFrame == true) {
                                        loadError = "${error?.errorCode}: ${error?.description}"
                                    }
                                }
                            }
                            isFocusable = true
                            isClickable = true
                            try { loadUrl(entryUrl) } catch (_: Throwable) {}
                            webView = this
                        }
                    } catch (e: Throwable) {
                        android.widget.TextView(ctx).apply {
                            text = "系统 WebView 不可用（${e.javaClass.simpleName}）"
                            setPadding(24, 32, 24, 32)
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
                onRelease = { v ->
                    try {
                        (v as? android.webkit.WebView)?.destroy()
                    } catch (_: Throwable) {}
                    webView = null
                },
            )
            if (missing) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(8.dp)) {
                    PluginMissingBlock(compact = true)
                }
            } else if (loadError != null) {
                Column(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        "侧边栏加载失败",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        loadError ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        entryUrl,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "重试",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable {
                            loadError = null
                            ready = false
                            try { webView?.reload() } catch (_: Throwable) {}
                        },
                    )
                }
            } else if (!ready) {
                Column(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "正在加载侧边栏面板…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Cuts the host page down to its workbench panel.
 *
 * Class prefixes are hashed per build, so the panel is found by its stable
 * `nArs4W_` module prefix rather than an exact selector; if the plugin renames it
 * the script simply leaves the panel where the host put it.
 */
private const val SIDEBAR_JS = """
(function () {
  var opened = false;
  // Body children present before we start = the app chrome (hidden). Anything the
  // page appends later is a popup/portal (pane picker, menus) and must stay visible,
  // otherwise the panel's own buttons look dead: they open an invisible menu.
  var chrome = null;
  var chainNodes = null;

  function openDefaultPane() {
    if (opened) return true;
    // 文件变动 first: that is the view worth having on a phone (edited files with
    // their +/− counts and the commit list). 文件 is the fallback.
    var cards = document.querySelectorAll('[class*="nArs4W_paneCard"]');
    var i, t;
    for (i = 0; i < cards.length; i++) {
      t = (cards[i].textContent || '').trim();
      if (t.indexOf('文件变动') >= 0) { cards[i].click(); opened = true; return true; }
    }
    for (i = 0; i < cards.length; i++) {
      t = (cards[i].textContent || '').trim();
      if (t === '文件' || t.indexOf('文件') === 0) { cards[i].click(); opened = true; return true; }
    }
    return false;
  }

  function isolate() {
    var panel = document.querySelector('[class*="nArs4W_bottomPanel"]')
      || document.querySelector('[class*="nArs4W_workbench"]');
    if (!panel) return false;
    // The host hides this panel at phone widths.
    panel.className = String(panel.className).replace(/nArs4W_bottomPanelHidden/g, '');

    if (!chrome) {
      chrome = new Set();
      for (var k = 0; k < document.body.children.length; k++) chrome.add(document.body.children[k]);
    }
    var chain = new Set();
    var el = panel;
    while (el && el !== document.body) { chain.add(el); el = el.parentElement; }
    chainNodes = chain;

    for (var i = 0; i < document.body.children.length; i++) {
      var c = document.body.children[i];
      if (chain.has(c)) continue;
      // Only ever hide the chrome we snapshotted; portals added later are left alone.
      if (!chrome.has(c)) continue;
      if (c.style.display !== 'none') c.style.display = 'none';
    }
    chain.forEach(function (n) {
      n.style.position = 'fixed';
      n.style.inset = '0';
      n.style.width = '100%';
      n.style.height = '100%';
      n.style.maxWidth = 'none';
      n.style.maxHeight = 'none';
      n.style.transform = 'none';
      // Low z-index on purpose: the plugin's popups must render above this panel.
      n.style.zIndex = '1';
      n.style.display = 'flex';
      n.style.flexDirection = 'column';
    });
    document.querySelectorAll('.ofm_toasts,.dsh-sat-btn').forEach(function (n) {
      n.style.display = 'none';
    });
    // The pane chooser is a vertical list at phone widths; the desktop shows a grid
    // of square tiles, which is what this view mimics.
    if (!document.getElementById('dsh-pane-grid')) {
      var st = document.createElement('style');
      st.id = 'dsh-pane-grid';
      st.textContent =
        '[class*="nArs4W_paneEmptyCards"]{display:grid!important;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px;align-content:start;}'
        + '[class*="nArs4W_paneEmptyCards"]>button{width:100%!important;height:auto!important;aspect-ratio:1/1;justify-content:center;align-items:center;';
      try { document.head.appendChild(st); } catch (e) {}
    }
    // 侧边对话 and 终端 do not work in this cut-down view (the terminal needs a live
    // PTY and never gets one here), so their tiles are removed rather than left as
    // dead buttons.
    document.querySelectorAll('[class*="nArs4W_paneCard"]').forEach(function (c) {
      var t = (c.textContent || '');
      if (t.indexOf('侧边对话') >= 0 || t.indexOf('终端') >= 0) c.style.display = 'none';
    });
    try { if (window.DshPanel && window.DshPanel.ready) window.DshPanel.ready(); } catch (e) {}
  try { if (!window.__dshNoHarvest) harvestChanges(); } catch (e) {}
    return true;
  }

  /**
   * Read the 文件变动 numbers once per load, then put the pane grid back.
   *
   * The grid never contains the numbers, and the chat footer needs them; opening the
   * pane for a moment and closing it again is the only way to get them out of the
   * page without pointing the Web UI at a specific session.
   */
  var harvested = false;
  function harvestChanges() {
    if (harvested) return;
    harvested = true;
    setTimeout(function () {
      try {
        window.__dshOpenPane && window.__dshOpenPane('文件变动');
      } catch (e) {}
      setTimeout(function () {
        try { reportChanges(); } catch (e) {}
        try {
          var close = document.querySelector('[class*="nArs4W_tabClose"]');
          if (close) close.click();
        } catch (e) {}
      }, 1800);
    }, 600);
  }

  /** Called from the native pane switcher; works for both the grid and open tabs. */
  window.__dshOpenPane = function (name) {
    if (!name) return false;
    var clicked = false, i, el;
    var cards = document.querySelectorAll('[class*="nArs4W_paneCard"]');
    for (i = 0; i < cards.length; i++) {
      if ((cards[i].textContent || '').indexOf(name) >= 0) { cards[i].click(); clicked = true; break; }
    }
    if (!clicked) {
      var tabs = document.querySelectorAll('button');
      for (i = 0; i < tabs.length; i++) {
        el = tabs[i];
        if ((el.textContent || '').trim() === name) { el.click(); clicked = true; break; }
      }
    }
    // xterm (终端) and the side conversation measure their container when they
    // mount; opening them inside a freshly re-laid-out panel leaves them at 0×0 and
    // looking dead. Nudge them to re-measure.
    if (clicked) {
      [150, 500, 1200].forEach(function (ms) {
        setTimeout(function () {
          try {
            window.dispatchEvent(new Event('resize'));
            if (window.ResizeObserver) { /* the plugin listens to window resize */ }
          } catch (e) {}
        }, ms);
      });
    }
    return clicked;
  };

  /**
   * Ship the 文件变动 summary out of the page.
   *
   * The chat footer should read like the desktop rail ("已编辑 2 个文件 +1") even
   * when the panel has never been opened, so the numbers are taken from the page
   * itself whenever this view has rendered them.
   */
  var lastReport = '';
  function reportChanges() {
    try {
      if (!window.DshPanel || !window.DshPanel.changes) return;
      var wb = document.querySelector('[class*="nArs4W_workbench"]');
      if (!wb) return;
      var txt = (wb.textContent || '').replace(/\s+/g, ' ');
      var m = txt.match(/已编辑\s*\d+\s*个文件/);
      if (!m) return;
      var summary = m[0];
      var nums = txt.match(/[+]\s*\d+|\u2212\s*\d+|-\s*\d+/g);
      if (nums) summary += ' ' + nums.slice(0, 2).join(' ');
      var files = [];
      var rows = wb.querySelectorAll('[class*="bdiHEa_rowMain"],[class*="rowMain"]');
      for (var i = 0; i < rows.length && files.length < 6; i++) {
        var t = (rows[i].textContent || '').trim();
        if (t && t.length < 120 && files.indexOf(t) < 0) files.push(t);
      }
      var payload = summary + '\u0002' + files.join('\u0001');
      if (payload === lastReport) return;
      lastReport = payload;
      window.DshPanel.changes(payload);
    } catch (e) {}
  }

  var tries = 0;
  var t = setInterval(function () {
    if (isolate() || ++tries > 200) clearInterval(t);
  }, 250);
  try {
    new MutationObserver(function () { isolate(); }).observe(document.documentElement, {
      childList: true, subtree: true
    });
  } catch (e) {}

  // Nothing to crop means the plugin is absent: tell the app so it can offer the
  // install hint instead of showing an empty box forever.
  var waited = 0;
  var probe = setInterval(function () {
    if (document.querySelector('[class*="nArs4W_workbench"]')
      || document.querySelector('[class*="nArs4W_bottomPanel"]')) {
      clearInterval(probe);
      return;
    }
    waited += 500;
    if (waited >= 12000) {
      clearInterval(probe);
      try { if (window.DshPanel && window.DshPanel.missing) window.DshPanel.missing(); } catch (e) {}
    }
  }, 500);
})();
"""

/** One label/value row inside the session card. */
@Composable
private fun InfoLine(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(52.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.labelSmall,
            color = valueColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Tappable row used by the shortcut list. */
@Composable
private fun PanelAction(label: String, icon: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DshIcon(icon, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 16.dp, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

/** mm:ss / h:mm:ss for a run duration. */
private fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Composable
private fun TodoCard(todos: List<dev.dsh.remote.data.TodoItem>) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DshIcon(DshIcons.Checklist, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 14.dp)
            Spacer(Modifier.width(4.dp))
            Text(
                Strings.str("todos_count", todos.size),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(4.dp))
        for (t in todos) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 1.dp)) {
                Box(
                    Modifier
                        .size(8.dp)
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
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun GoalCard(vm: AppViewModel, goal: GoalInfo) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(DshGreen.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DshIcon(DshIcons.Goal, tint = DshGreen, size = 14.dp)
            Spacer(Modifier.width(4.dp))
            Text(
                Strings.str("goal_phase", goal.phase),
                color = DshGreen,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            goal.objective,
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 6,
            overflow = TextOverflow.Ellipsis,
        )
        when (goal.phase) {
            "active" -> {
                TextButton(onClick = { vm.goalPause() }) { Text(Strings.str("pause"), style = MaterialTheme.typography.labelSmall) }
                TextButton(onClick = { vm.goalComplete() }) { Text(Strings.str("complete"), style = MaterialTheme.typography.labelSmall) }
            }
            "paused" -> {
                TextButton(onClick = { vm.goalResume() }) { Text(Strings.str("resume"), style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

@Composable
private fun JobsRow(jobs: List<JobView>) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DshIcon(DshIcons.Loading, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 14.dp)
            Spacer(Modifier.width(4.dp))
            Text(
                Strings.str("background_jobs"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(6.dp))
        for (j in jobs) {
            JobCard(j)
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun JobCard(job: JobView) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background, RoundedCornerShape(6.dp))
            .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
            .padding(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            JobIndicator(job.status)
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    job.kind.ifBlank { Strings.str("task") },
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (job.label.isNotBlank()) {
                    Text(
                        job.label,
                        color = MaterialTheme.colorScheme.onBackground,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            jobFooter(job),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun JobIndicator(status: String) {
    val running = status == "running" || status == "stopping"
    val color = when (status) {
        "completed", "killed" -> DshGreen
        "failed" -> DshRed
        else -> MaterialTheme.colorScheme.primary
    }
    if (running) {
        CircularProgressIndicator(
            modifier = Modifier.size(10.dp),
            strokeWidth = 2.dp,
            color = color,
        )
    } else {
        Box(
            Modifier
                .size(10.dp)
                .background(color, CircleShape),
        )
    }
}

private fun jobFooter(job: JobView): String {
    val statusText = when (job.status) {
        "completed" -> Strings.str("status_completed")
        "killed" -> Strings.str("status_killed")
        "failed" -> Strings.str("status_failed")
        "stopping" -> Strings.str("status_stopping")
        else -> Strings.str("status_running")
    }
    val parts = mutableListOf(statusText)
    if (!job.detail.isNullOrBlank()) parts.add(job.detail)
    val duration = job.finishedAt?.let { fin ->
        if (job.startedAt > 0 && fin > job.startedAt) fin - job.startedAt else null
    }
    if (duration != null) parts.add(formatDuration(duration))
    return parts.joinToString(" · ")
}

private fun formatDuration(ms: Long): String {
    val sec = ms / 1000
    return if (sec < 60) Strings.str("secs_fmt", sec) else Strings.str("min_sec_fmt", sec / 60, sec % 60)
}
