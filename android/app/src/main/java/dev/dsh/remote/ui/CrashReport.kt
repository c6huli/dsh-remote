package dev.dsh.remote.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.dsh.remote.ui.theme.LocalDshSemantic
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 上次启动崩溃的记录。
 *
 * 之前崩溃日志写在应用私有目录里，人找不到、我也拿不到，于是"闪退"只能靠猜。
 * 现在把它在**下一次启动时直接显示出来**——可复制、可截图，问题才可定位。
 *
 * 只在存在记录时弹一次；关掉即删除，避免每次启动都糊一屏。
 */
@Composable
fun CrashReportDialog() {
    val sem = LocalDshSemantic.current
    val clipboard = LocalClipboardManager.current
    var report by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        report = withContext(Dispatchers.IO) { readCrashReport() }
    }

    val text = report ?: return

    AlertDialog(
        onDismissRequest = { /* 必须点按钮关闭，避免误触丢失信息 */ },
        title = { Text("上次启动崩溃了", fontWeight = FontWeight.SemiBold, color = sem.danger) },
        text = {
            Column {
                Text(
                    "下面是崩溃堆栈，可复制后发给开发者定位。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(text))
                clearCrashReport()
                report = null
            }) { Text("复制并关闭") }
        },
        dismissButton = {
            TextButton(onClick = {
                clearCrashReport()
                report = null
            }) { Text("关闭") }
        },
    )
}

/** Set once from MainActivity so this screen needs no plumbing through the tree. */
var appContextRef: Context? = null

private fun crashFile(context: Context): File =
    File(context.getExternalFilesDir(null) ?: context.cacheDir, "crash-log.txt")

private fun readCrashReport(): String? {
    val ctx = appContextRef ?: return null
    val f = crashFile(ctx)
    if (!f.exists()) return null
    return try {
        val all = f.readText()
        // Newest entries are appended last: keep the tail so the dialog shows the
        // crash that matters rather than the oldest one.
        val text = if (all.length > MAX_REPORT_CHARS) all.takeLast(MAX_REPORT_CHARS) else all
        text.ifBlank { null }
    } catch (_: Throwable) {
        null
    }
}

private fun clearCrashReport() {
    val ctx = appContextRef ?: return
    runCatching { crashFile(ctx).delete() }
}

private const val MAX_REPORT_CHARS = 4000
