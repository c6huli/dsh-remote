package dev.dsh.remote.phone

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The phone-control screen's state holder: status, the mirroring loop and the
 * gestures. Everything privileged happens in [PhoneControl] (Shizuku shell side);
 * this class only decides *when* to ask and keeps the UI in sync.
 *
 * Mirroring is a poll, not a stream: each frame is one `screencap` on the shell
 * side, so it stops the moment the person leaves the screen or switches the
 * feature off. That is deliberate - nothing should keep grabbing the screen in
 * the background.
 */
class PhoneSession(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    /**
     * Shared engine handle.
     *
     * The instruction runner borrows this instead of building a second
     * PhoneControl: two instances would mean two bound Shizuku user services
     * for the same job, and the shell side is a single privileged channel by
     * design.
     */
    val control = PhoneControl(context)

    private val _status = MutableStateFlow(control.status())
    val status: StateFlow<PhoneControl.PhoneStatus> = _status.asStateFlow()

    private val _frame = MutableStateFlow<Bitmap?>(null)
    val frame: StateFlow<Bitmap?> = _frame.asStateFlow()

    private val _apps = MutableStateFlow<List<PhoneControl.AppEntry>>(emptyList())
    val apps: StateFlow<List<PhoneControl.AppEntry>> = _apps.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Foreground package reported by the shell side, for a "you are here" line. */
    private val _foreground = MutableStateFlow<String?>(null)
    val foreground: StateFlow<String?> = _foreground.asStateFlow()

    private var mirrorJob: Job? = null

    // ---- 1.6.3 新增状态 ----
    private val _brightness = MutableStateFlow(-1)
    val brightness: StateFlow<Int> = _brightness.asStateFlow()

    private val _volume = MutableStateFlow(-1)
    val volume: StateFlow<Int> = _volume.asStateFlow()

    private val _recents = MutableStateFlow<List<String>>(emptyList())
    val recents: StateFlow<List<String>> = _recents.asStateFlow()

    private val _notifications = MutableStateFlow<List<String>>(emptyList())
    val notifications: StateFlow<List<String>> = _notifications.asStateFlow()

    private val _clipboard = MutableStateFlow<String?>(null)
    val clipboard: StateFlow<String?> = _clipboard.asStateFlow()

    private val _deviceInfo = MutableStateFlow<String?>(null)
    val deviceInfo: StateFlow<String?> = _deviceInfo.asStateFlow()

    /** Wires Shizuku's lifecycle callbacks so the page reflects state changes. */
    fun observe() {
        control.ensureListeners { _status.value = control.status() }
        refreshStatus()
    }

    fun refreshStatus() {
        _status.value = control.status()
    }

    /** Binds the shell service off the main thread; safe to call repeatedly. */
    fun connect() {
        if (_busy.value) return
        _busy.value = true
        scope.launch {
            try {
                val channel = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    control.connect()
                }
                _message.value = if (channel != null) null else "特权通道未连接"
            } catch (t: Throwable) {
                _message.value = t.message ?: "连接失败"
            } finally {
                _busy.value = false
                refreshStatus()
            }
        }
    }

    /** Asks Shizuku for the shell permission, then connects on approval. */
    fun requestPermission() {
        val shown = control.requestPermission { granted ->
            _message.value = if (granted) null else "授权被拒绝"
            refreshStatus()
            if (granted) connect()
        }
        if (!shown) {
            _message.value = if (control.installed()) "Shizuku 未运行，请先启动 Shizuku 服务" else "未安装 Shizuku"
            refreshStatus()
        }
    }

    fun disconnect() {
        stopMirror()
        control.disconnect()
        refreshStatus()
    }

    // ---- mirroring ----

    fun startMirror() {
        if (mirrorJob?.isActive == true) return
        mirrorJob = scope.launch {
            var failures = 0
            while (isActive) {
                val bitmap = control.screenshot()
                if (bitmap != null) {
                    _frame.value = bitmap
                    failures = 0
                    if (_foreground.value == null) {
                        _foreground.value = control.foregroundPackage()
                    }
                } else {
                    failures++
                    if (failures == 3) {
                        _message.value = "截屏失败，请检查 Shizuku 授权"
                        refreshStatus()
                    }
                }
                delay(if (failures > 0) 1500L else FRAME_MS)
            }
        }
    }

    fun stopMirror() {
        mirrorJob?.cancel()
        mirrorJob = null
    }

    /** One extra frame right after an input event, so the tap feels acknowledged. */
    private fun refreshFrameSoon() {
        scope.launch {
            delay(180)
            val bitmap = control.screenshot() ?: return@launch
            _frame.value = bitmap
        }
    }

    // ---- input ----

    fun tap(x: Int, y: Int) = act { control.tap(x, y) }

    fun swipe(x: Int, y: Int, x2: Int, y2: Int, ms: Int) = act { control.swipe(x, y, x2, y2, ms) }

    fun text(value: String) = act { control.text(value) }

    fun key(name: String) = act { control.key(name) }

    fun launch(pkg: String) = act {
        control.launchApp(pkg)
        delay(600)
        _foreground.value = control.foregroundPackage()
    }

    // ---- 1.6.3:更完整的操控面 ----

    fun longPress(x: Int, y: Int, ms: Int = 600) = act { control.longPress(x, y, ms) }

    fun doubleTap(x: Int, y: Int) = act { control.doubleTap(x, y) }

    fun scroll(direction: String, distance: Int = 5) = act { control.scroll(direction, distance) }

    fun drag(x: Int, y: Int, x2: Int, y2: Int) = act { control.drag(x, y, x2, y2) }

    fun hardKey(name: String) = act { control.hardKey(name) }

    fun rotate(orientation: Int) = act {
        control.rotate(orientation)
        delay(300)
        refreshStatus()
    }

    fun setBrightness(level: Int) = act { control.brightness(level) }

    fun readBrightness() = act { _brightness.value = control.brightness(-1) }

    fun keepAwake(on: Boolean) = act { control.keepAwake(on) }

    fun setVolume(stream: String = "music", level: Int) = act { control.volume(stream, level) }

    fun readVolume(stream: String = "music") = act { _volume.value = control.volume(stream, -1) }

    fun loadRecents() = act { _recents.value = control.recents() }

    fun clearBackground() = act {
        control.clearBackground()
        delay(400)
        _recents.value = control.recents()
    }

    fun loadNotifications() = act { _notifications.value = control.notifications() }

    fun forceStop(pkg: String) = act { control.forceStop(pkg) }

    fun uninstall(pkg: String) = act {
        control.uninstall(pkg)
        delay(400)
        _apps.value = control.apps()
    }

    fun loadClipboard() = act { _clipboard.value = control.getClipboard() }

    fun setClipboard(text: String) = act { control.setClipboard(text) }

    fun loadDeviceInfo() = act { _deviceInfo.value = control.deviceInfo() }

    fun toggle(feature: String, on: Boolean) = act { control.toggle(feature, on) }

    fun loadAllApps() = act { _apps.value = control.allApps() }
    fun loadApps() {
        scope.launch {
            try {
                _apps.value = control.apps()
            } catch (t: Throwable) {
                _message.value = t.message ?: "读取应用列表失败"
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    private fun act(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
                refreshFrameSoon()
            } catch (t: Throwable) {
                _message.value = t.message ?: "操作失败"
            }
        }
    }

    companion object {
        /** Poll interval: slow enough not to burn battery, fast enough to feel live. */
        private const val FRAME_MS = 900L
    }
}
