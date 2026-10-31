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
import dev.dsh.remote.ui.theme.DshTheme
import java.io.File

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Let Compose own the window insets: the chat's imePadding can then lift the
        // composer above the keyboard instead of the whole window being resized.
        try {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        } catch (_: Throwable) {}
        installCrashLogger()
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
        // A tapped notification deep-links into its conversation.
        intent.getStringExtra(DshForegroundService.EXTRA_SESSION_ID)?.let { vm.openSessionWhenReady(it) }
        if (intent.getBooleanExtra("dsh.openDownloads", false)) vm.requestOpenDownloads()
        setContent {
            val themePreference by vm.themePreference.collectAsState()
            DshTheme(preference = themePreference) {
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
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val dir = getExternalFilesDir(null) ?: cacheDir
                val file = File(dir, "crash-log.txt")
                val entry = buildString {
                    appendLine("=== ${java.util.Date()} ===")
                    appendLine(throwable.toString())
                    for (el in throwable.stackTrace) appendLine("  at $el")
                    appendLine()
                }
                val existing = if (file.exists()) file.readText() else ""
                file.writeText(existing + entry)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            if (granted != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }
    }
}
