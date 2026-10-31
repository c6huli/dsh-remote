@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.dsh.remote.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import dev.dsh.remote.ui.icons.DshIcon
import dev.dsh.remote.ui.icons.DshIcons

/** One file already sitting in the download folder. */
data class DownloadEntry(
    val uri: String,
    val name: String,
    val size: Long,
    val mime: String,
)

/**
 * Files this app has downloaded, opened with whatever app handles their type.
 *
 * The list is read from wherever downloads currently go: the SAF folder picked in
 * Settings, or MediaStore's Download/<folder> when the default location is used.
 */
@Composable
fun DownloadsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf<List<DownloadEntry>?>(null) }
    var pendingDelete by remember { mutableStateOf<DownloadEntry?>(null) }


    LaunchedEffect(Unit) { entries = vm.listDownloads() }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                DshIcon(DshIcons.ChevronLeft, tint = MaterialTheme.colorScheme.onSurface, size = 22.dp, contentDescription = "返回")
            }
            Text("已下载文件", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { entries = null }, enabled = false) { Text("") }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))

        val list = entries
        when {
            list == null -> Text(
                "正在读取…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            list.isEmpty() -> Column(Modifier.padding(16.dp)) {
                Text("还没有下载过文件", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    vm.downloadTreeLabel(vm.downloadTree.value) + "\n下载位置可在设置里更换",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(list) { e ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { vm.openDownloaded(e) },
                                onLongClick = { pendingDelete = e },
                            )
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DshIcon(DshIcons.FolderOpen, tint = MaterialTheme.colorScheme.primary, size = 18.dp, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                e.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                formatSize(e.size) + " · " + e.mime,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        TextButton(onClick = { vm.openDownloaded(e) }) { Text("打开") }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                }
            }
        }
    }

    pendingDelete?.let { target ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除文件") },
            text = { Text(target.name + "\n\n删除后无法恢复。") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    pendingDelete = null
                    // Refresh after the delete settles so the row disappears.
                    kotlinx.coroutines.MainScope().launch {
                        vm.deleteDownloaded(target)
                        entries = vm.listDownloads()
                    }
                }) { Text("删除") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1048576.0)
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
