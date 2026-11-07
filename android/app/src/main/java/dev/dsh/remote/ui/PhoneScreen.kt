package dev.dsh.remote.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.dsh.remote.phone.PhoneCommandRunner
import dev.dsh.remote.phone.PhoneCommandRunnerHolder
import dev.dsh.remote.phone.PhoneControl
import dev.dsh.remote.phone.PhoneStep
import dev.dsh.remote.phone.PhoneStopOverlayService
import dev.dsh.remote.ui.icons.DshIcon
import dev.dsh.remote.ui.icons.DshIcons
import dev.dsh.remote.ui.theme.AppShape
import dev.dsh.remote.ui.theme.AppSpace
import dev.dsh.remote.ui.theme.Eyebrow
import dev.dsh.remote.ui.theme.GlassIconButton
import dev.dsh.remote.ui.theme.GlassPill
import dev.dsh.remote.ui.theme.LocalDshSemantic
import dev.dsh.remote.ui.theme.PrimaryButton
import dev.dsh.remote.ui.theme.SecondaryButton
import dev.dsh.remote.ui.theme.SettingsCard
import dev.dsh.remote.ui.theme.VSpace

/**
 * 手机操控 —— 指令输入页。
 *
 * 这里不镜像屏幕、不摆一堆音量/亮度按钮：就是一格输入框，写一句要做的事
 * （「打开微信」「点 500 800」「返回」…），点发送后由 [PhoneCommandRunner]
 * 在后台按顺序执行；执行期间屏幕上会浮出一个「紧急停止」小圆钮，点一下就中断。
 *
 * 权限仍然来自 Shizuku（shell 身份），没授权时整页只显示引导。
 * 「高级」里保留了原来的镜像/按键面板，需要时再展开。
 */
@Composable
fun PhoneScreen(vm: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val phoneSession = remember { vm.phoneSession() }
    val status by phoneSession.status.collectAsState()
    val message by phoneSession.message.collectAsState()

    val runner = remember { vm.phoneCommandRunner() }
    val running by runner.running.collectAsState()
    val planning by runner.planning.collectAsState()
    val plannerUsed by runner.plannerUsed.collectAsState()
    val riskPrompt by runner.risk.collectAsState()
    val log by runner.log.collectAsState()

    var input by remember { mutableStateOf("") }
    var showMirror by remember { mutableStateOf(false) }
    var overlayGranted by remember { mutableStateOf(PhoneStopOverlayService.canDraw(context)) }

    LaunchedEffect(Unit) {
        phoneSession.observe()
        if (phoneSession.status.value.granted) phoneSession.connect()
        PhoneCommandRunnerHolder.install(runner)
    }
    // 从系统"显示在其他应用上层"页返回时权限才真正生效；继续用旧值会让提示
    // 一直挂着。这次重新读一次系统设置。
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                overlayGranted = PhoneStopOverlayService.canDraw(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(Unit) {
        onDispose {
            // 离开页面只停镜像；正在跑的指令可以继续（它有自己的悬浮停止钮）。
            phoneSession.stopMirror()
        }
    }

    val sem = LocalDshSemantic.current
    val scheme = MaterialTheme.colorScheme

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding()
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        // ---- 顶栏 ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(icon = DshIcons.ChevronLeft, onClick = onBack)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "手机操控",
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleLarge,
                    color = scheme.onSurface,
                )
                Text(
                    when {
                        status.ready -> "已连接 · 指令在后台执行"
                        status.granted -> "待连接"
                        status.running -> "待授权"
                        status.installed -> "Shizuku 未运行"
                        else -> "未安装 Shizuku"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (status.ready) sem.success else sem.textTertiary,
                )
            }
            GlassPill(text = "刷新", onClick = { phoneSession.refreshStatus() })
            Spacer(Modifier.width(6.dp))
            // 镜像只是"看一眼"，不接管前台：执行照常继续。
            GlassPill(
                text = if (showMirror) "关镜像" else "镜像",
                selected = showMirror,
                enabled = status.ready,
            ) { showMirror = !showMirror }
        }

        message?.let { text ->
            VSpace(AppSpace.sm)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(AppShape.md))
                    .background(sem.dangerContainer)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = sem.onDangerContainer,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { phoneSession.clearMessage() }) { Text("知道了") }
            }
        }

        if (!status.ready) {
            VSpace(AppSpace.sm)
            SetupCard(
                status = status,
                onRequest = { phoneSession.requestPermission() },
                onConnect = { phoneSession.connect() },
            )
        }

        VSpace(AppSpace.md)

        // ---- 输入框 ----
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 96.dp, max = 200.dp),
            label = { Text("想让它做什么（说人话就行）") },
            placeholder = { Text("帮我打开微信，进设置看一下\n给这个页面截个图之前的界面点返回\n翻到最上面") },
            enabled = status.ready && !running,
            shape = RoundedCornerShape(AppShape.md),
        )

        VSpace(AppSpace.sm)

        Row(verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton(
                text = if (planning) "分析意图…" else if (running) "执行中…" else "执行",
                enabled = status.ready && !running && input.isNotBlank(),
                onClick = {
                    val ok = runner.run(input)
                    if (ok) {
                        // Bring up the floating stop button for the duration.
                        if (PhoneStopOverlayService.canDraw(context)) {
                            PhoneStopOverlayService.start(context)
                        }
                    }
                },
            )
            Spacer(Modifier.width(8.dp))
            if (running) {
                SecondaryButton(text = "紧急停止", onClick = {
                    runner.stop()
                    PhoneStopOverlayService.stop(context)
                })
            } else {
                SecondaryButton(text = "清空日志", onClick = { runner.clearLog() })
            }
            Spacer(Modifier.weight(1f))
            if (running || planning) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        }

        // 上滑挂后台：执行期间随时把页面收走，指令在后台继续跑。
        if (running) {
            VSpace(AppSpace.xs)
            Text(
                "上滑回桌面可挂到后台，执行会继续；需要时点悬浮按钮紧急停止",
                style = MaterialTheme.typography.labelSmall,
                color = sem.textTertiary,
            )
        }

        // ---- 悬浮窗权限提示 ----
        if (!overlayGranted) {
            VSpace(AppSpace.xs)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "开启悬浮窗后，执行期间会有「紧急停止」按钮浮在屏幕上",
                    style = MaterialTheme.typography.labelSmall,
                    color = sem.textTertiary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + context.packageName),
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                    overlayGranted = PhoneStopOverlayService.canDraw(context)
                }) { Text("开启") }
            }
        }

        // ---- 镜像(只读预览，不接管前台，不影响正在执行的指令) ----
        if (showMirror) {
            VSpace(AppSpace.sm)
            MirrorPane(phoneSession, status)
        }

        if (plannerUsed.isNotBlank() && log.isNotEmpty()) {
            VSpace(AppSpace.xs)
            Text(
                "意图由「$plannerUsed」解析",
                style = MaterialTheme.typography.labelSmall,
                color = sem.textTertiary,
            )
        }

        // ---- 执行日志 ----
        if (log.isNotEmpty()) {
            VSpace(AppSpace.md)
            Eyebrow("执行记录")
            VSpace(AppSpace.xs)
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                itemsIndexed(log) { _, entry ->
                    val color = when {
                        entry.ok -> scheme.onSurfaceVariant
                        else -> sem.danger
                    }
                    Column(Modifier.padding(vertical = 2.dp)) {
                        Text(
                            (if (entry.ok) "✓ " else "✗ ") + entry.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = color,
                        )
                        entry.error?.let {
                            Text(
                                "   " + it,
                                style = MaterialTheme.typography.labelSmall,
                                color = sem.danger,
                            )
                        }
                    }
                }
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
    }

    // 危险操作审核：命中支付/下单/验证码/个人信息等，先停下来问人。
    riskPrompt?.let { prompt ->
        RiskConfirmDialog(
            prompt = prompt,
            onConfirm = { runner.confirmRisk() },
            onCancel = { runner.rejectRisk() },
        )
    }
}

/**
 * 危险操作确认框。
 *
 * 它出现在"即将动手"的那一刻，而且必须由人点「确认执行」才会继续——不给
 * 自动通过、不给倒计时、不给"记住我的选择"，因为这类动作没有第二次机会。
 */
@Composable
private fun RiskConfirmDialog(
    prompt: PhoneCommandRunner.RiskPrompt,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val sem = LocalDshSemantic.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { /* 必须明确选择，不能点外面关掉 */ },
        title = {
            Text(
                "⚠️ 危险操作，需要你确认",
                fontWeight = FontWeight.SemiBold,
                color = sem.danger,
            )
        },
        text = {
            Column {
                Text(
                    "接下来这一步：" + prompt.nextAction,
                    style = MaterialTheme.typography.bodyMedium,
                )
                VSpace(AppSpace.sm)
                Text(
                    prompt.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = sem.danger,
                )
                if (prompt.hits.isNotEmpty()) {
                    VSpace(AppSpace.sm)
                    Text(
                        "涉及类别：" + prompt.hits.map { it.category.label }.distinct().joinToString("、"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                VSpace(AppSpace.sm)
                Text(
                    "确认前请核对金额、收款方和商品；不确定就点取消。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("确认执行", color = sem.danger, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("取消") }
        },
    )
}

/**
 * 只读镜像预览。
 *
 * 它是"看一眼现在屏幕上是什么"，不是操控面板：不拦触摸、不抢前台，正在跑的
 * 指令照常在后台继续。离开页面就停，不在后台留一个抓屏循环。
 */
@Composable
private fun MirrorPane(session: dev.dsh.remote.phone.PhoneSession, status: PhoneControl.PhoneStatus) {
    val frame by session.frame.collectAsState()
    val sem = LocalDshSemantic.current

    // 只读镜像：进来看一眼，不是操控面板，也不把前台交出去。
    LaunchedEffect(status.ready) {
        if (status.ready) session.startMirror() else session.stopMirror()
    }
    DisposableEffect(Unit) { onDispose { session.stopMirror() } }

    val bitmap = frame
    if (bitmap != null) {
        val image = remember(bitmap) { bitmap.asImageBitmap() }
        androidx.compose.foundation.Image(
            bitmap = image,
            contentDescription = "手机屏幕",
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp)
                .clip(RoundedCornerShape(AppShape.md)),
            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
        )
    } else {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp)
                .clip(RoundedCornerShape(AppShape.md))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (status.ready) "等待第一帧…" else "需要 Shizuku 授权",
                style = MaterialTheme.typography.labelSmall,
                color = sem.textTertiary,
            )
        }
    }
}

/**
 * 未就绪时的引导卡片:先装 Shizuku、再启动、再授权,三步之外没有别的门槛。
 */
@Composable
private fun SetupCard(
    status: PhoneControl.PhoneStatus,
    onRequest: () -> Unit,
    onConnect: () -> Unit,
) {
    val sem = LocalDshSemantic.current
    SettingsCard(
        subtitle = "Shizuku 用一次「无线调试」把 shell 权限借给本应用,之后不用电脑、不用数据线、" +
            "不用 root。授权只用于点按、滑动、输入文字、启动应用这几件事。",
    ) {
        Step("1", "安装 Shizuku(Google Play 或 GitHub 的 RikkaApps/Shizuku)", status.installed)
        Step("2", "打开 Shizuku,按它首页的指引启动服务(Android 11+ 用无线调试配对)", status.running)
        Step("3", "回到这里点「请求授权」,在 Shizuku 弹窗里允许", status.granted)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PrimaryButton(text = "请求授权", enabled = status.running, onClick = onRequest)
            SecondaryButton(text = "连接特权通道", enabled = status.granted, onClick = onConnect)
        }
        if (!status.installed) {
            Text(
                "还没装 Shizuku?先在应用商店或 GitHub 安装,再回到本页。",
                style = MaterialTheme.typography.labelSmall,
                color = sem.textTertiary,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun Step(index: String, text: String, done: Boolean) {
    val sem = LocalDshSemantic.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (done) sem.success else sem.textDisabled.copy(alpha = 0.25f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(index, style = MaterialTheme.typography.labelSmall, color = Color.White)
        }
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = LocalContentColor.current)
    }
}
