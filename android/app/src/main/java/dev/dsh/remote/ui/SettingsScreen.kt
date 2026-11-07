package dev.dsh.remote.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.dsh.remote.BuildConfig
import dev.dsh.remote.ui.icons.DshIcon
import dev.dsh.remote.ui.icons.DshIcons
import dev.dsh.remote.ui.theme.AppAccent
import dev.dsh.remote.ui.theme.AppShape
import dev.dsh.remote.ui.theme.AppSpace
import dev.dsh.remote.ui.theme.Eyebrow
import dev.dsh.remote.ui.theme.GlassPill
import dev.dsh.remote.ui.theme.GlassSurface
import dev.dsh.remote.ui.theme.GlassTier
import dev.dsh.remote.ui.theme.LocalDshSemantic
import dev.dsh.remote.ui.theme.LocalDshSurfaces
import dev.dsh.remote.ui.theme.PrimaryButton
import dev.dsh.remote.ui.theme.SecondaryButton
import dev.dsh.remote.ui.theme.SectionGap
import dev.dsh.remote.ui.theme.SettingsCard
import dev.dsh.remote.ui.theme.SettingsRow
import dev.dsh.remote.ui.theme.VSpace
import dev.dsh.remote.ui.theme.pressableClick

/**
 * 设置页:分组玻璃卡 + 顶栏玻璃圆钮。
 *
 * 结构规则:
 * - 每组一块卡,卡片之间 20dp,不画分割线(卡本身的发丝边就够了);
 * - 组标题用小字号 + 字距(eyebrow),不是粗体大字;
 * - 一行 = 图标 + 标题/说明 + 右侧控件,整行可点,按压缩放。
 */
@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit, onOpenDrawer: () -> Unit = {}) {
    val serverUrl by vm.serverUrl.collectAsState()
    val connected by vm.connected.collectAsState()
    val error by vm.error.collectAsState()
    val notifyDone by vm.notifyDone.collectAsState()
    val notifyPrompt by vm.notifyPrompt.collectAsState()
    val themePreference by vm.themePreference.collectAsState()
    val language by vm.language.collectAsState()
    val verbose by vm.verbose.collectAsState()
    val phoneControl by vm.phoneControl.collectAsState()
    val downloadTree by vm.downloadTree.collectAsState()
    val accentId by vm.accentId.collectAsState()
    val glassBlur by vm.glassBlur.collectAsState()

    var url by remember { mutableStateOf(serverUrl) }
    var showBalance by remember { mutableStateOf(false) }
    var showWebSettings by remember { mutableStateOf(false) }
    var showDownloads by remember { mutableStateOf(false) }
    var showPhone by remember { mutableStateOf(false) }
    val wantDownloads by vm.openDownloads.collectAsState()
    LaunchedEffect(wantDownloads) {
        if (wantDownloads) {
            showDownloads = true
            vm.consumeOpenDownloads()
        }
    }
    val context = androidx.compose.ui.platform.LocalContext.current

    if (showPhone) {
        PhoneScreen(vm, onBack = { showPhone = false })
        return
    }
    if (showDownloads) {
        DownloadsScreen(vm, onBack = { showDownloads = false })
        return
    }
    if (showWebSettings) {
        WebSettingsScreen(vm, onBack = { showWebSettings = false }, onOpenDrawer = onOpenDrawer)
        return
    }
    if (showBalance) {
        BalanceScreen(vm, onBack = { showBalance = false })
        return
    }

    val scheme = MaterialTheme.colorScheme
    val sem = LocalDshSemantic.current

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AppSpace.gutter, vertical = AppSpace.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlassSurface(
                tier = GlassTier.Chrome,
                shape = CircleShape,
                modifier = Modifier.size(40.dp),
                onClick = onBack,
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    DshIcon(DshIcons.ChevronLeft, tint = scheme.onSurface, size = 20.dp)
                }
            }
            Spacer(Modifier.width(8.dp))
            GlassSurface(
                tier = GlassTier.Chrome,
                shape = CircleShape,
                modifier = Modifier.size(40.dp),
                onClick = onOpenDrawer,
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    DshIcon(DshIcons.PanelLeft, tint = scheme.onSurface, size = 19.dp)
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                Strings.str("settings"),
                style = MaterialTheme.typography.headlineMedium,
                color = scheme.onSurface,
            )
        }

        VSpace(AppSpace.xl)

        // ---- 连接 ----
        Eyebrow(Strings.str("server_url"))
        VSpace(AppSpace.sm)
        GlassSurface(
            tier = GlassTier.Card,
            shape = RoundedCornerShape(AppShape.lg),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(14.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(Strings.str("server_url_hint")) },
                    singleLine = true,
                    shape = RoundedCornerShape(AppShape.md),
                )
                VSpace(AppSpace.xs)
                Text(
                    Strings.str("server_url_example"),
                    color = sem.textTertiary,
                    style = MaterialTheme.typography.bodySmall,
                )
                VSpace(AppSpace.md)
                PrimaryButton(
                    text = Strings.str("save_and_connect"),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        vm.setServerUrl(url)
                        vm.connect(url)
                    },
                )
                VSpace(AppSpace.sm)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (connected) sem.success else sem.textDisabled),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (connected) Strings.str("connected") else Strings.str("disconnected"),
                        color = if (connected) sem.success else sem.textTertiary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                error?.let {
                    VSpace(AppSpace.xs)
                    Text(it, color = scheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        SectionGap()

        // ---- 外观 ----
        Eyebrow("外观")
        VSpace(AppSpace.sm)
        SettingsCard {
            SettingsRow(
                title = "主题",
                subtitle = "跟随系统 / 浅色 / 深色",
                icon = DshIcons.Light,
            )
            Box(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                AppearanceRow(current = themePreference, onSelect = vm::setThemePreference)
            }
            VSpace(AppSpace.sm)
            SettingsRow(
                title = "配色",
                subtitle = "强调色 + 整套中性色一起换,对比度不变",
                icon = DshIcons.Data,
            )
            Box(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                AccentRow(current = accentId, onSelect = vm::setAccentId)
            }
            VSpace(AppSpace.sm)
            SettingsRow(
                title = "玻璃透明",
                subtitle = "关闭后玻璃改为近乎不透明,更省电",
                icon = DshIcons.Sparkle,
                trailing = {
                    Switch(checked = glassBlur, onCheckedChange = { vm.setGlassBlur(it) })
                },
            )
            SettingsRow(
                title = Strings.str("language"),
                subtitle = null,
                icon = DshIcons.Globe,
            )
            Box(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                LanguageRow(current = language, onSelect = vm::setLanguage)
            }
            VSpace(AppSpace.xs)
        }

        SectionGap()

        // ---- 会话 ----
        Eyebrow("会话")
        VSpace(AppSpace.sm)
        SettingsCard {
            SettingsRow(
                title = "详细显示过程",
                subtitle = "开:展开思考与工具步骤(同 Web 端);关:简洁视图",
                icon = DshIcons.Think,
                trailing = {
                    Switch(checked = verbose, onCheckedChange = { vm.setVerbose(it) })
                },
            )
            SettingsRow(
                title = Strings.str("notify_done"),
                subtitle = Strings.str("notify_done_sub"),
                icon = DshIcons.Check,
                trailing = {
                    Switch(checked = notifyDone, onCheckedChange = { vm.setNotifyDone(it) })
                },
            )
            SettingsRow(
                title = Strings.str("notify_prompt"),
                subtitle = Strings.str("notify_prompt_sub"),
                icon = DshIcons.Question,
                trailing = {
                    Switch(checked = notifyPrompt, onCheckedChange = { vm.setNotifyPrompt(it) })
                },
            )
        }

        SectionGap()

        // ---- 手机操控 ----
        Eyebrow("手机操控")
        VSpace(AppSpace.sm)
        SettingsCard(
            subtitle = "用 Shizuku 借到的 shell 权限在这台手机上截屏、点按、滑动、输入文字、" +
                "启动应用。默认关闭;打开后仍要 Shizuku 自己弹窗授权。不需要 root,也不需要数据线。",
        ) {
            SettingsRow(
                title = "开启手机操控",
                subtitle = if (phoneControl) "已开启,可从侧边栏进入「手机操控」" else "关闭时应用不会接触 Shizuku",
                icon = DshIcons.Data,
                trailing = {
                    Switch(checked = phoneControl, onCheckedChange = { vm.setPhoneControl(it) })
                },
            )
            if (phoneControl) {
                // agent 端点:告诉用户端口与 token 在哪,以及怎么把它接给桌面。
                val bridge = vm.phoneBridgeInfo()
                SettingsRow(
                    title = "agent 操控端点",
                    subtitle = if (bridge.first) {
                        "127.0.0.1:${bridge.second} 已监听 · 桌面需 adb reverse tcp:${bridge.second} tcp:${bridge.second}"
                    } else {
                        "未监听(打开上面的开关并完成授权后自动启动)"
                    },
                    icon = DshIcons.Api,
                )
                if (bridge.third.isNotBlank()) {
                    SettingsRow(
                        title = "端点 token",
                        subtitle = bridge.third,
                        icon = DshIcons.Code,
                    )
                }
                Box(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                    SecondaryButton(
                        text = "进入手机操控",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { showPhone = true },
                    )
                }
            }
        }

        SectionGap()

        // ---- Web 端 ----
        Eyebrow("Web 端")
        VSpace(AppSpace.sm)
        SettingsCard {
            SettingsRow(
                title = Strings.str("open_web_settings"),
                subtitle = Strings.str("web_settings"),
                icon = DshIcons.Settings,
                onClick = { showWebSettings = true },
                trailing = { DshIcon(DshIcons.ChevronRight, tint = sem.textTertiary, size = 16.dp) },
            )
        }

        SectionGap()

        // ---- 文件 ----
        Eyebrow("文件")
        VSpace(AppSpace.sm)
        SettingsCard {
            SettingsRow(
                title = "保存位置",
                subtitle = vm.downloadTreeLabel(downloadTree),
                icon = DshIcons.Folder,
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val treePicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocumentTree(),
                ) { uri ->
                    if (uri != null) {
                        try {
                            context.contentResolver.takePersistableUriPermission(
                                uri,
                                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                            )
                        } catch (_: Throwable) {
                        }
                        vm.setDownloadTree(uri.toString())
                    }
                }
                GlassPill(text = "选择文件夹", onClick = { treePicker.launch(null) })
                GlassPill(text = "用默认", onClick = { vm.setDownloadTree("") })
            }
            SettingsRow(
                title = "已下载文件",
                subtitle = "查看已下载的文件,可用其它应用打开",
                icon = DshIcons.Browse,
                onClick = { showDownloads = true },
                trailing = { DshIcon(DshIcons.ChevronRight, tint = sem.textTertiary, size = 16.dp) },
            )
        }

        SectionGap()

        // ---- DeepSeek 平台 ----
        Eyebrow(Strings.str("deepseek_platform"))
        VSpace(AppSpace.sm)
        SettingsCard {
            Box(Modifier.padding(14.dp)) {
                SecondaryButton(
                    text = Strings.str("check_balance"),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { showBalance = true },
                )
            }
        }

        SectionGap()

        // ---- 关于 ----
        Eyebrow(Strings.str("about"))
        VSpace(AppSpace.sm)
        SettingsCard {
            SettingsRow(
                title = "DSH Remote",
                subtitle = "v${BuildConfig.VERSION_NAME} · 雾蓝玻璃界面",
                icon = DshIcons.Fish,
            )
        }
        VSpace(AppSpace.xxxl)
    }
}

@Composable
private fun LanguageRow(current: String, onSelect: (String) -> Unit) {
    val options = listOf(
        Strings.ZH to "中文",
        Strings.EN to "English",
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((value, label) in options) {
            SegmentCell(
                label = label,
                selected = current == value,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(value) },
            )
        }
    }
}

@Composable
private fun AppearanceRow(current: String, onSelect: (String) -> Unit) {
    val options = listOf(
        "light" to Strings.str("light"),
        "dark" to Strings.str("dark"),
        "system" to Strings.str("follow_system"),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((value, label) in options) {
            SegmentCell(
                label = label,
                selected = current == value,
                modifier = Modifier.weight(1f),
                icon = when (value) {
                    "light" -> DshIcons.Light
                    "dark" -> DshIcons.Dark
                    else -> DshIcons.FollowSystem
                },
                onClick = { onSelect(value) },
            )
        }
    }
}

/** 配色选择:六个色点 + 名称,横向滚动。 */
@Composable
private fun AccentRow(current: String, onSelect: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val sem = LocalDshSemantic.current
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (accent in AppAccent.presets) {
            val selected = current == accent.id
            val label = when (accent.id) {
                "mist" -> "雾蓝"
                "daiqing" -> "黛青"
                "dusk" -> "暮紫"
                "turquoise" -> "松石"
                "amber" -> "琥珀"
                "graphite" -> "石墨"
                else -> accent.id
            }
            Column(
                Modifier
                    .width(62.dp)
                    .clip(RoundedCornerShape(AppShape.md))
                    .background(if (selected) scheme.primary.copy(alpha = 0.12f) else Color.Transparent)
                    .pressableClick(onClick = { onSelect(accent.id) })
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(26.dp).clip(CircleShape).background(accent.seed),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        Text(
                            "✓",
                            style = MaterialTheme.typography.labelSmall,
                            color = accent.onSeed,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                VSpace(AppSpace.xs)
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) scheme.primary else sem.textTertiary,
                )
            }
        }
    }
}

/** 分段选择里的一格:玻璃底 + 选中主色。 */
@Composable
private fun SegmentCell(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    icon: Int? = null,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val sem = LocalDshSemantic.current
    val surfaces = LocalDshSurfaces.current
    GlassSurface(
        tier = GlassTier.Card,
        shape = RoundedCornerShape(AppShape.md),
        modifier = modifier,
        style = surfaces.card.copy(
            fill = if (selected) scheme.primary.copy(alpha = 0.14f) else surfaces.card.fill,
            fallbackFill = if (selected) scheme.primary.copy(alpha = 0.14f) else surfaces.card.fallbackFill,
            hairline = if (selected) scheme.primary.copy(alpha = 0.32f) else surfaces.card.hairline,
        ),
        onClick = onClick,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (icon != null) {
                DshIcon(
                    icon,
                    tint = if (selected) scheme.primary else sem.textTertiary,
                    size = 18.dp,
                )
                VSpace(AppSpace.xs)
            }
            Text(
                label,
                color = if (selected) scheme.primary else scheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}
