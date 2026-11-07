package dev.dsh.remote

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import dev.dsh.remote.service.DshForegroundService
import dev.dsh.remote.ui.AppRoot
import dev.dsh.remote.ui.AppViewModel
import dev.dsh.remote.ui.appContextRef
import dev.dsh.remote.ui.theme.DshTheme
import java.io.File

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 诊断模式：进程启动时崩过一次，这里直接用最朴素的方式把原因显示出来。
        // 刻意不经过 Compose / 主题 / ViewModel——那些正是可能崩的地方。
        if (intent.getBooleanExtra(EXTRA_SHOW_CRASH, false)) {
            showCrashReport()
            return
        }

        // Let Compose own the window insets: the chat's imePadding can then lift the
        // composer above the keyboard instead of the whole window being resized.
        try {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        } catch (_: Throwable) {}
        installCrashLogger()
        // 预热 TLS：SSLContext + SecureRandom 的首次初始化可能很慢（冷启动熵池
        // 不足时尤其明显）。放到后台线程先从主线程上挪走，否则它会在首帧之前
        // 卡住主线程，表现就是白屏/黑屏、连开机动画都出不来。
        runCatching { dev.dsh.remote.net.TrustAll.warmUpAsync() }
        requestNotificationPermission()
        // Start the foreground service at launch, not after a successful connect:
        // it owns the background socket, and starting it late meant a killed process
        // left nothing behind to notice a finished turn.
        try {
            androidx.core.content.ContextCompat.startForegroundService(
                this,
                Intent(this, DshForegroundService::class.java),
            )
        } catch (_: Throwable) {
        }
        // AppViewModel.init() already calls connect() with DEFAULT_URL
        // A tapped notification deep-links into its conversation. Each of these
        // touches the view model, so one malformed extra must not abort onCreate —
        // the app would otherwise close before drawing a single frame.
        runCatching {
            intent.getStringExtra(DshForegroundService.EXTRA_SESSION_ID)?.let { vm.openSessionWhenReady(it) }
        }
        runCatching {
            if (intent.getBooleanExtra("dsh.openDownloads", false)) vm.requestOpenDownloads()
        }
        // 边到边:系统栏全透明,内容自己避让(状态栏 / 手势条)。
        try {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes.layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        } catch (_: Throwable) {}
        setContent {
            val themePreference by vm.themePreference.collectAsState()
            val accentId by vm.accentId.collectAsState()
            val glassBlur by vm.glassBlur.collectAsState()
            // A bad saved theme (unknown accent id left by an older build) must not
            // take the whole tree down; DshTheme itself guards, this is the outer net.
            DshTheme(
                preference = themePreference,
                accentId = accentId,
                glassBlur = glassBlur,
            ) {
                AppRoot(vm)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Reaching the app means these reminders did their job: the turn-finished
        // one from the service and the download-finished one from the view model.
        try {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            nm?.cancel(DshForegroundService.DONE_NOTIFY_ID)
            nm?.cancel(4712)
        } catch (_: Throwable) {
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Notification tapped while the activity is already alive → jump to that session.
        if (intent.getBooleanExtra("dsh.openDownloads", false)) vm.requestOpenDownloads()
        intent.getStringExtra(DshForegroundService.EXTRA_SESSION_ID)?.let { vm.openSessionWhenReady(it) }
    }

    /** Persist uncaught crashes to a file so they can be diagnosed after the fact. */
    private fun installCrashLogger() {
        // The report screen is reached without plumbing a Context through the tree.
        appContextRef = applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val entry = try {
                writeCrashEntry(thread, throwable)
            } catch (_: Throwable) {
                null
            }
            // 启动阶段崩溃：与其让进程直接消失（"打都打不开"），不如跳去诊断页，
            // 把堆栈显示出来。只在一个进程里尝试一次，避免崩溃→重启→崩溃的循环。
            val duringStartup =
                android.os.SystemClock.uptimeMillis() - processStartedAtMs < STARTUP_CRASH_WINDOW_MS
            if (duringStartup && crashRelaunchAttempted.compareAndSet(false, true)) {
                val launched = runCatching {
                    startActivity(
                        Intent(this, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            .putExtra(EXTRA_SHOW_CRASH, true),
                    )
                }.isSuccess
                if (launched) {
                    // Swallow this one: the diagnostic screen is now the foreground.
                    return@setDefaultUncaughtExceptionHandler
                }
            }
            if (entry == null) {
                // Nothing recorded and nothing to show; behave normally.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Append a full crash record (with context) to the log file. */
    private fun writeCrashEntry(thread: Thread, throwable: Throwable): String {
        val dir = getExternalFilesDir(null) ?: cacheDir
        val file = File(dir, "crash-log.txt")
        val entry = buildString {
            appendLine("=== ${java.util.Date()} ===")
            appendLine("thread=${thread.name}  app=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})")
            appendLine(
                "device=${Build.MANUFACTURER} ${Build.MODEL}  " +
                    "android=${Build.VERSION.RELEASE}(${Build.VERSION.SDK_INT})"
            )
            appendLine(throwable.toString())
            for (el in throwable.stackTrace) appendLine("  at $el")
            // Chained causes usually carry the real reason.
            var cause = throwable.cause
            var depth = 0
            while (cause != null && depth < 5) {
                appendLine("Caused by: $cause")
                for (el in cause.stackTrace.take(40)) appendLine("  at $el")
                cause = cause.cause
                depth++
            }
            appendLine()
        }
        val existing = if (file.exists()) file.readText() else ""
        // Bound the file: it only needs to survive until the next launch.
        file.writeText((existing + entry).takeLast(200_000))
        return entry
    }

    /**
     * 诊断页：纯系统控件，不碰 Compose / 主题 / ViewModel。
     *
     * 只要能显示出堆栈，就比"闪退什么都不留"有价值——它既是给用户看的解释，
     * 也是我能拿到的唯一线索。
     */
    private fun showCrashReport() {
        val log = runCatching {
            val dir = getExternalFilesDir(null) ?: cacheDir
            val f = File(dir, "crash-log.txt")
            if (f.exists()) f.readText().takeLast(6000) else ""
        }.getOrDefault("")
        val text = if (log.isBlank()) "没有记录到崩溃信息。" else log

        val scroll = android.widget.ScrollView(this)
        val body = android.widget.TextView(this).apply {
            this.text = text
            textSize = 11f
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(32, 32, 32, 32)
        }
        scroll.addView(body)
        val bar = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(24, 16, 24, 16)
            addView(
                android.widget.TextView(this@MainActivity).apply {
                    this.text = "上次启动崩溃了（可长按选择复制，或点右侧按钮）"
                    textSize = 13f
                },
                android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                android.widget.Button(this@MainActivity).apply {
                    this.text = "复制"
                    setOnClickListener {
                        val cm = getSystemService(android.content.ClipboardManager::class.java)
                        cm?.setPrimaryClip(android.content.ClipData.newPlainText("crash", text))
                    }
                },
            )
        }
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(bar)
            addView(scroll, android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            if (granted != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }
    }

    companion object {
        /** Launch the activity straight into the crash-report view. */
        const val EXTRA_SHOW_CRASH = "dsh.showCrash"

        /** Crashes this soon after process start count as "startup crashes". */
        private const val STARTUP_CRASH_WINDOW_MS = 10_000L

        /** Captured when this class is first touched, ≈ process start. */
        private val processStartedAtMs = android.os.SystemClock.uptimeMillis()

        /** Guards against a crash → relaunch → crash loop. */
        private val crashRelaunchAttempted = java.util.concurrent.atomic.AtomicBoolean(false)
    }
}
