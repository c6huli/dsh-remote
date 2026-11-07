package dev.dsh.remote.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Floating "emergency stop" button shown while a typed phone instruction runs.
 *
 * Small, draggable, always on top: one tap cancels whatever [PhoneCommandRunner]
 * is doing and dismisses itself. It never leaves the app's own overlay budget -
 * it is a single VIEW window, no accessibility service, no background capture.
 */
class PhoneStopOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForegroundQuietly()
        showOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_AND_HIDE) {
            PhoneCommandRunnerHolder.stop()
            hideAndStop()
            return START_NOT_STICKY
        }
        showOverlay()
        return START_STICKY
    }

    override fun onDestroy() {
        hideOverlay()
        super.onDestroy()
    }

    // ---- overlay ----

    private fun canOverlay(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || android.provider.Settings.canDrawOverlays(this)

    private fun showOverlay() {
        if (view != null) return
        if (!canOverlay()) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm
        val sizePx = (56 * resources.displayMetrics.density).toInt()

        val button = TextView(this).apply {
            text = "■"
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#D93025"))
                setStroke((2 * resources.displayMetrics.density).toInt(), Color.argb(120, 255, 255, 255))
            }
        }
        val label = TextView(this).apply {
            text = "紧急停止"
            setTextColor(Color.WHITE)
            textSize = 10f
            gravity = Gravity.CENTER
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = 28f * resources.displayMetrics.density
                setColor(Color.argb(210, 30, 30, 30))
            }
            addView(button, LinearLayout.LayoutParams(sizePx, sizePx))
            addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            setPadding(14, 14, 14, 10)
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (12 * resources.displayMetrics.density).toInt()
            y = (resources.displayMetrics.heightPixels * 0.55f).toInt()
        }

        // Drag to move, tap to stop.
        container.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var startX = 0
            private var startY = 0
            private var moved = false
            override fun onTouch(v: View?, e: MotionEvent?): Boolean {
                val ev = e ?: return false
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = ev.rawX; downY = ev.rawY
                        startX = lp.x; startY = lp.y
                        moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = ev.rawX - downX
                        val dy = ev.rawY - downY
                        if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) moved = true
                        if (moved) {
                            lp.x = startX + dx.toInt()
                            lp.y = startY + dy.toInt()
                            runCatching { windowManager?.updateViewLayout(container, lp) }
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) emergencyStop()
                    }
                }
                return true
            }
        })

        runCatching {
            wm.addView(container, lp)
            view = container
            params = lp
        }.onFailure {
            view = null
            stopSelf()
        }
    }

    private fun hideOverlay() {
        val v = view ?: return
        view = null
        runCatching { windowManager?.removeView(v) }
    }

    private fun hideAndStop() {
        hideOverlay()
        stopSelf()
    }

    private fun emergencyStop() {
        PhoneCommandRunnerHolder.stop()
        hideAndStop()
    }

    // ---- foreground notification (Android requires one for background lifetime) ----

    private fun startAsForegroundQuietly() {
        runCatching {
            val channelId = "dsh-phone-stop"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val mgr = getSystemService(NotificationManager::class.java)
                mgr?.createNotificationChannel(
                    NotificationChannel(channelId, "手机操控执行中", NotificationManager.IMPORTANCE_LOW).apply {
                        setShowBadge(false)
                    }
                )
            }
            val stopIntent = PendingIntent.getService(
                this,
                0,
                Intent(this, PhoneStopOverlayService::class.java).setAction(ACTION_STOP_AND_HIDE),
                PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0),
            )
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                Notification.Builder(this, channelId)
            else
                @Suppress("DEPRECATION") Notification.Builder(this)
            val notification = builder
                .setContentTitle("手机操控执行中")
                .setContentText("点悬浮按钮或这里可紧急停止")
                .setSmallIcon(android.R.drawable.ic_menu_close_clear_cancel)
                .setOngoing(true)
                .addAction(
                    Notification.Action.Builder(null as android.graphics.drawable.Icon?, "紧急停止", stopIntent).build()
                )
                .build()
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 0x0D5A
        const val ACTION_STOP_AND_HIDE = "dev.dsh.remote.phone.STOP_AND_HIDE"

        /** True when the person granted the "显示在其他应用上层" permission. */
        fun canDraw(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                android.provider.Settings.canDrawOverlays(context)

        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, PhoneStopOverlayService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, PhoneStopOverlayService::class.java)) }
        }
    }
}

/**
 * Process-wide handle to the running instruction, so the floating button (a
 * separate Service) can cancel the same job the page started — and so the
 * runner can take the button away again the moment there is nothing left to stop.
 */
object PhoneCommandRunnerHolder {
    @Volatile
    private var runner: PhoneCommandRunner? = null

    @Volatile
    private var appContext: android.content.Context? = null

    fun install(r: PhoneCommandRunner) {
        runner = r
        appContext = r.appContext()
    }

    fun stop() {
        runner?.stop()
    }

    /**
     * Called when an instruction finishes (normally or by stop).
     *
     * Without this the floating button outlived the work it guards: the person
     * taps it and nothing happens because there is no longer a job behind it.
     */
    fun hideOverlay() {
        val ctx = appContext ?: return
        PhoneStopOverlayService.stop(ctx)
    }
}
