package dev.dsh.remote.phone

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * App-side entry to phone control.
 *
 * Shizuku runs [PhoneControlService] as the **shell** user and hands this
 * process its Binder; every call below is one Binder round trip to that
 * process. Nothing here needs a rooted phone and nothing needs adb: if the
 * person installed Shizuku (and started it once via wireless debugging or
 * root), the same shell channel is available to us.
 *
 * All failures collapse into [PhoneStatus] / exceptions with a short reason, so
 * the UI can say "Shizuku 未运行" instead of crashing.
 */
class PhoneControl(private val context: Context) {

    /** What the settings page needs to render its state. */
    data class PhoneStatus(
        val installed: Boolean,
        val running: Boolean,
        val granted: Boolean,
        val connected: Boolean,
        val shellUid: Int,
        val width: Int,
        val height: Int,
        val reason: String?,
    ) {
        val ready: Boolean get() = connected && granted
    }

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var remote: IPhoneControl? = null

    @Volatile
    private var connection: ServiceConnection? = null

    @Volatile
    private var args: Shizuku.UserServiceArgs? = null

    @Volatile
    private var listenersAdded = false

    @Volatile
    private var lastWidth = 0

    @Volatile
    private var lastHeight = 0

    private val userServiceArgs: Shizuku.UserServiceArgs
        get() = Shizuku.UserServiceArgs(
            ComponentName(context, PhoneControlService::class.java),
        )
            .tag("dsh-phone")
            .processNameSuffix("phone")
            .version(1)
            .daemon(false)

    // ---- status ----

    fun installed(): Boolean = runCatching {
        context.packageManager.getApplicationInfo(SHIZUKU_PACKAGE, 0)
        true
    }.getOrDefault(false)

    fun running(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun granted(): Boolean = runCatching {
        running() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun status(): PhoneStatus {
        val installed = installed()
        val running = running()
        val granted = running && granted()
        val connected = remote?.let { runCatching { it.asBinder().pingBinder() }.getOrDefault(false) } == true
        val reason = when {
            connected && granted -> null
            !running -> if (installed) "Shizuku 未运行，请先在 Shizuku 里启动服务" else "未安装 Shizuku"
            !granted -> "Shizuku 授权未通过，请点“请求授权”"
            else -> "特权通道未连接"
        }
        return PhoneStatus(installed, running, granted, connected, shellUid(), lastWidth, lastHeight, reason)
    }

    private fun shellUid(): Int = runCatching { Shizuku.getUid() }.getOrDefault(-1)

    /** Registers the Shizuku lifecycle callbacks once, so the UI can refresh itself. */
    fun ensureListeners(onChanged: () -> Unit) {
        if (listenersAdded) return
        listenersAdded = true
        runCatching {
            Shizuku.addBinderReceivedListenerSticky { onChanged() }
            Shizuku.addBinderDeadListener {
                remote = null
                onChanged()
            }
            Shizuku.addRequestPermissionResultListener { _, _ -> onChanged() }
        }
    }

    /** Shows Shizuku's own permission dialog. Returns false when it cannot be shown. */
    fun requestPermission(onResult: (Boolean) -> Unit): Boolean {
        if (!running()) return false
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode != REQUEST_CODE) return
                runCatching { Shizuku.removeRequestPermissionResultListener(this) }
                onResult(grantResult == PackageManager.PERMISSION_GRANTED)
            }
        }
        return runCatching {
            Shizuku.addRequestPermissionResultListener(listener)
            Shizuku.requestPermission(REQUEST_CODE)
            true
        }.getOrElse {
            runCatching { Shizuku.removeRequestPermissionResultListener(listener) }
            false
        }
    }

    /**
     * Binds the shell-side service. Blocking (up to [BIND_TIMEOUT_S]) because the
     * caller always runs it off the main thread.
     */
    fun connect(): IPhoneControl? {
        remote?.let { existing ->
            if (runCatching { existing.asBinder().pingBinder() }.getOrDefault(false)) return existing
        }
        if (!granted()) return null
        val latch = CountDownLatch(1)
        val holder = arrayOfNulls<IBinder>(1)
        val serviceArgs = userServiceArgs
        val listener = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (connection !== this) return
                holder[0] = binder
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (connection !== this) return
                remote = null
            }
        }
        connection = listener
        args = serviceArgs
        main.post { runCatching { Shizuku.bindUserService(serviceArgs, listener) } }
        if (!latch.await(BIND_TIMEOUT_S.toLong(), TimeUnit.SECONDS)) {
            disconnect()
            return null
        }
        val stub = IPhoneControl.Stub.asInterface(holder[0]) ?: run {
            disconnect()
            return null
        }
        // Prove identity and revision before trusting the channel.
        val probe = runCatching {
            stub.execute(Bundle().apply { putString(PhoneProto.OP, PhoneProto.OP_PROBE) })
        }.getOrNull()
        if (probe?.getBoolean(PhoneProto.OK) != true) {
            disconnect()
            return null
        }
        lastWidth = probe.getInt(PhoneProto.WIDTH)
        lastHeight = probe.getInt(PhoneProto.HEIGHT)
        remote = stub
        return stub
    }

    fun disconnect() {
        remote = null
        val conn = connection ?: return
        val serviceArgs = args
        connection = null
        args = null
        if (serviceArgs != null) {
            main.post { runCatching { Shizuku.unbindUserService(serviceArgs, conn, true) } }
        }
    }

    // ---- operations (all blocking; call from Dispatchers.IO) ----

    private fun require(): IPhoneControl =
        remote?.takeIf { runCatching { it.asBinder().pingBinder() }.getOrDefault(false) }
            ?: connect()
            ?: error("特权通道未连接")

    private fun call(op: String, block: Bundle.() -> Unit = {}): Bundle {
        val request = Bundle().apply {
            putString(PhoneProto.OP, op)
            block()
        }
        val response = require().execute(request)
        if (!response.getBoolean(PhoneProto.OK)) {
            val reason = response.getString(PhoneProto.REASON)
            error(reason?.takeIf { it.isNotBlank() } ?: (response.getString(PhoneProto.ERROR) ?: "操作失败"))
        }
        return response
    }

    /** One frame as a Bitmap, or null when the capture failed. */
    suspend fun screenshot(): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val head = call(PhoneProto.OP_SCREENSHOT)
            val total = head.getLong(PhoneProto.BYTES).toInt()
            if (total <= 0) return@withContext null
            val out = java.io.ByteArrayOutputStream(total)
            var offset = 0
            while (offset < total) {
                val len = minOf(CHUNK, total - offset)
                val piece = call(PhoneProto.OP_READ) {
                    putInt(PhoneProto.OFFSET, offset)
                    putInt(PhoneProto.LEN, len)
                }
                val bytes = piece.getByteArray(PhoneProto.CHUNK_DATA) ?: break
                if (bytes.isEmpty()) break
                out.write(bytes)
                offset += bytes.size
            }
            val data = out.toByteArray()
            if (data.isEmpty()) null else BitmapFactory.decodeByteArray(data, 0, data.size)
        } catch (_: Throwable) {
            null
        }
    }

    suspend fun tap(x: Int, y: Int) = withContext(Dispatchers.IO) {
        call(PhoneProto.OP_TAP) {
            putInt(PhoneProto.X, x)
            putInt(PhoneProto.Y, y)
        }
        Unit
    }

    suspend fun swipe(x: Int, y: Int, x2: Int, y2: Int, ms: Int = 250) = withContext(Dispatchers.IO) {
        call(PhoneProto.OP_SWIPE) {
            putInt(PhoneProto.X, x)
            putInt(PhoneProto.Y, y)
            putInt(PhoneProto.X2, x2)
            putInt(PhoneProto.Y2, y2)
            putInt(PhoneProto.MS, ms)
        }
        Unit
    }

    suspend fun text(value: String) = withContext(Dispatchers.IO) {
        call(PhoneProto.OP_TEXT) { putString(PhoneProto.TEXT, value) }
        Unit
    }

    suspend fun key(name: String) = withContext(Dispatchers.IO) {
        call(PhoneProto.OP_KEY) { putString(PhoneProto.KEY, name) }
        Unit
    }

    suspend fun launchApp(pkg: String) = withContext(Dispatchers.IO) {
        call(PhoneProto.OP_LAUNCH) { putString(PhoneProto.PKG, pkg) }
        Unit
    }

    data class AppEntry(val label: String, val pkg: String)

    suspend fun apps(): List<AppEntry> = withContext(Dispatchers.IO) {
        val response = call(PhoneProto.OP_APPS)
        val packages = response.getStringArray(PhoneProto.PACKAGES) ?: return@withContext emptyList()
        val labels = response.getStringArray(PhoneProto.LABELS) ?: emptyArray()
        packages.mapIndexed { index, pkg ->
            AppEntry(labels.getOrNull(index)?.takeIf { it.isNotBlank() } ?: pkg, pkg)
        }.sortedBy { it.label.lowercase() }
    }

    // ---- 1.6.3:更完整的操控面 ----

    suspend fun longPress(x: Int, y: Int, ms: Int = 600) = withContext(Dispatchers.IO) {
        call(PhoneProto.OP_LONG_PRESS) {
            putInt(PhoneProto.X, x)
            putInt(PhoneProto.Y, y)
            putInt(PhoneProto.MS, ms)
        }
        Unit
    }

    suspend fun doubleTap(x: Int, y: Int) = withContext(Dispatchers.IO) {
        call(PhoneProto.OP_DOUBLE_TAP) {
            putInt(PhoneProto.X, x)
            putInt(PhoneProto.Y, y)
        }
        Unit
    }

    suspend fun scroll(direction: String, distance: Int = 5) = withContext(Dispatchers.IO) {
        call(PhoneProto.OP_SCROLL) {
            putString(PhoneProto.DIRECTION, direction)
            putInt(PhoneProto.DISTANCE, distance)
        }
        Unit
    }

    suspend fun drag(x: Int, y: Int, x2: Int, y2: Int) = withContext(Dispatchers.IO) {
        call(PhoneProto.OP_DRAG) {
            putInt(PhoneProto.X, x)
            putInt(PhoneProto.Y, y)
            putInt(PhoneProto.X2, x2)
            putInt(PhoneProto.Y2, y2)
        }
        Unit
    }

    suspend fun hardKey(name: String) = withContext(Dispatchers.IO) {
        call(PhoneProto.OP_HARD_KEY) { putString(PhoneProto.KEY, name) }
        Unit
    }

    suspend fun rotate(orientation: Int) = withContext(Dispatchers.IO) {
        call(PhoneProto.OP_ROTATE) { putInt(PhoneProto.ORIENTATION, orientation) }
        Unit
    }

    /** 传 -1 读取当前亮度,0–255 设置。 */
    suspend fun brightness(level: Int = -1): Int = withContext(Dispatchers.IO) {
        call(PhoneProto.OP_BRIGHTNESS) { putInt(PhoneProto.LEVEL, level) }
            .getInt(PhoneProto.VALUE, -1)
    }

    suspend fun keepAwake(on: Boolean) = withContext(Dispatchers.IO) {
        call(PhoneProto.OP_KEEP_AWAKE) { putBoolean(PhoneProto.ON, on) }
        Unit
    }

    /** 传 -1 读取,否则设置。 */
    suspend fun volume(stream: String = "music", level: Int = -1): Int = withContext(Dispatchers.IO) {
        call(PhoneProto.OP_VOLUME) {
            putString(PhoneProto.STREAM, stream)
            putInt(PhoneProto.LEVEL, level)
        }.getInt(PhoneProto.VALUE, -1)
    }

    suspend fun recents(): List<String> = withContext(Dispatchers.IO) {
        val r = call(PhoneProto.OP_RECENTS)
        (r.getStringArray(PhoneProto.PACKAGES) ?: emptyArray()).toList()
    }

    suspend fun clearBackground() = withContext(Dispatchers.IO) {
        call(PhoneProto.OP_CLEAR_BG)
        Unit
    }

    suspend fun notifications(): List<String> = withContext(Dispatchers.IO) {
        val r = call(PhoneProto.OP_NOTIFICATIONS)
        (r.getStringArray(PhoneProto.TEXTS) ?: emptyArray()).toList()
    }

    /**
     * Visible text of the current screen, for the safety review layer.
     *
     * Returns an empty list when the dump itself is unavailable (some apps block
     * uiautomator), which the review layer treats as "cannot verify". Engine
     * failures are reported as null so a broken channel is not mistaken for an
     * unreadable screen. Cancellation always propagates — an emergency stop must
     * interrupt this call, not sit behind a swallowed exception.
     */
    suspend fun screenText(): List<String>? = withContext(Dispatchers.IO) {
        try {
            val r = call(PhoneProto.OP_SCREEN_TEXT)
            (r.getStringArray(PhoneProto.TEXTS) ?: emptyArray()).toList()
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (_: Throwable) {
            null
        }
    }

    /** Evidence that a verification code really arrived by SMS. */
    data class SmsCode(val found: Boolean, val detail: String)

    /**
     * Did a recent SMS containing digits arrive?
     *
     * null means "could not tell" (dump unavailable, unusual build). The review
     * layer treats that as unknown rather than as "no code" — but it also does
     * not turn every unreadable shade into a confirmation prompt.
     */
    suspend fun smsCode(): SmsCode? = withContext(Dispatchers.IO) {
        try {
            val r = call(PhoneProto.OP_SMS_CODE)
            SmsCode(
                found = r.getBoolean(PhoneProto.FOUND, false),
                detail = r.getString(PhoneProto.DETAIL) ?: "",
            )
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (_: Throwable) {
            null
        }
    }

    suspend fun forceStop(pkg: String) = withContext(Dispatchers.IO) {
        call(PhoneProto.OP_FORCE_STOP) { putString(PhoneProto.PKG, pkg) }
        Unit
    }

    suspend fun uninstall(pkg: String) = withContext(Dispatchers.IO) {
        call(PhoneProto.OP_UNINSTALL) { putString(PhoneProto.PKG, pkg) }
        Unit
    }

    suspend fun getClipboard(): String = withContext(Dispatchers.IO) {
        call(PhoneProto.OP_GET_CLIP).getString(PhoneProto.TEXT) ?: ""
    }

    suspend fun setClipboard(text: String) = withContext(Dispatchers.IO) {
        call(PhoneProto.OP_SET_CLIP) { putString(PhoneProto.TEXT, text) }
        Unit
    }

    suspend fun deviceInfo(): String = withContext(Dispatchers.IO) {
        call(PhoneProto.OP_DEVICE_INFO).getString(PhoneProto.TEXT) ?: ""
    }

    suspend fun toggle(feature: String, on: Boolean) = withContext(Dispatchers.IO) {
        call(PhoneProto.OP_TOGGLE) {
            putString(PhoneProto.FEATURE, feature)
            putBoolean(PhoneProto.ON, on)
        }
        Unit
    }

    /** 含系统应用的完整列表(设置里的"全部应用"用)。 */
    suspend fun allApps(): List<AppEntry> = withContext(Dispatchers.IO) {
        val response = call(PhoneProto.OP_INSTALLED) { putBoolean(PhoneProto.INCLUDING_SYSTEM, true) }
        val packages = response.getStringArray(PhoneProto.PACKAGES) ?: return@withContext emptyList()
        val labels = response.getStringArray(PhoneProto.LABELS) ?: emptyArray()
        packages.mapIndexed { index, pkg ->
            AppEntry(labels.getOrNull(index)?.takeIf { it.isNotBlank() } ?: pkg, pkg)
        }.sortedBy { it.label.lowercase() }
    }
    suspend fun foregroundPackage(): String? = withContext(Dispatchers.IO) {
        try {
            call(PhoneProto.OP_FOREGROUND).getString(PhoneProto.PKG)
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val REQUEST_CODE = 4601
        private const val BIND_TIMEOUT_S = 15

        /** Binder payload ceiling; mirrors the shell side's slice size. */
        const val CHUNK = 256 * 1024
    }
}
