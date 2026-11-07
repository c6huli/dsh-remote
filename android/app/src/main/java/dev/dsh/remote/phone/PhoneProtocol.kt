package dev.dsh.remote.phone

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel

/**
 * The single request/response contract the privileged side speaks.
 *
 * Deliberately *not* AIDL (no `buildFeatures { aidl }`, and the whole thing
 * type-checks as plain Kotlin): one method, a request [Bundle] in and a
 * response [Bundle] out. Every capability is an op inside the Bundle, so the
 * shell-side engine accepts only this fixed verb set - no shell commands, no
 * arbitrary paths, nothing that could be turned into "run whatever the host
 * says". The app itself can do nothing the user did not switch on in Settings.
 */
object PhoneProto {
    const val REVISION = "dsh-phone-1"

    // request keys
    const val OP = "op"
    const val REASON = "reason"

    // ops
    /** Handshake: proves identity + revision before anything else is allowed. */
    const val OP_PROBE = "probe"
    /** Capture a frame to a shell-owned PNG; returns its byte size. */
    const val OP_SCREENSHOT = "screenshot"
    /** Pull one slice of that PNG back to the app (Binder size limits). */
    const val OP_READ = "read"
    /** Tap at absolute screen pixels. */
    const val OP_TAP = "tap"
    /** Swipe / fling between two points. */
    const val OP_SWIPE = "swipe"
    /** Type text into the focused field. */
    const val OP_TEXT = "text"
    /** Send a global key event (back / home / recents / enter ...). */
    const val OP_KEY = "key"
    /** Launch an app by package name. */
    const val OP_LAUNCH = "launch"
    /** List installed launchable apps (label + package). */
    const val OP_APPS = "apps"
    /** Current foreground package, so the UI can say what is on screen. */
    const val OP_FOREGROUND = "foreground"
    /** Release whatever the engine still holds (screen capture session etc). */
    const val OP_RELEASE = "release"

    // ---- 1.6.3 扩展:更完整的操控面 ----
    /** 长按:按下、保持、抬起。 */
    const val OP_LONG_PRESS = "longPress"
    /** 双击。 */
    const val OP_DOUBLE_TAP = "doubleTap"
    /** 滚动:方向 + 距离,按屏幕比例换算成一次 swipe。 */
    const val OP_SCROLL = "scroll"
    /** 拖拽(慢速滑动,用于拖放 / 调整滑块)。 */
    const val OP_DRAG = "drag"
    /** 物理键:亮屏 / 熄屏 / 音量 / 静音 / 相机 / 手电。 */
    const val OP_HARD_KEY = "hardKey"
    /** 旋转屏幕到指定方向。 */
    const val OP_ROTATE = "rotate"
    /** 设置 / 读取屏幕亮度(0–255)。 */
    const val OP_BRIGHTNESS = "brightness"
    /** 屏幕常亮开关。 */
    const val OP_KEEP_AWAKE = "keepAwake"
    /** 音量:读取 / 设置各通道音量。 */
    const val OP_VOLUME = "volume"
    /** 最近任务列表。 */
    const val OP_RECENTS = "recents"
    /** 清理后台(am kill-all)。 */
    const val OP_CLEAR_BG = "clearBackground"
    /** 当前通知(状态栏里的)。 */
    const val OP_NOTIFICATIONS = "notifications"
    /** 已安装应用(含系统应用,带 label)。 */
    const val OP_INSTALLED = "installed"
    /** 卸载应用(走 shell pm uninstall,需用户确认)。 */
    const val OP_UNINSTALL = "uninstall"
    /** 强制停止应用。 */
    const val OP_FORCE_STOP = "forceStop"
    /** 读取剪贴板。 */
    const val OP_GET_CLIP = "getClipboard"
    /** 写入剪贴板。 */
    const val OP_SET_CLIP = "setClipboard"
    /** 设备信息(型号 / 版本 / 电量 / 存储)。 */
    const val OP_DEVICE_INFO = "deviceInfo"
    /** 飞行模式 / WiFi / 移动数据 / 蓝牙开关(需要 shell 权限)。 */
    const val OP_TOGGLE = "toggle"

    // ---- 危险操作审核 ----
    /**
     * 读取当前界面的可见文字(uiautomator dump)。
     *
     * 只读:不点、不滑、不改任何状态。审核层拿它判断"现在这个屏幕上是不是
     * 支付页/验证码框",因为坐标本身没有意义——危险的永远是点下去那一刻的界面。
     */
    const val OP_SCREEN_TEXT = "screenText"

    /**
     * 最近是否收到**含数字的短信**（即真的来了验证码）。
     *
     * 只读通知栏，不解密、不读取短信数据库全文，只回答"有没有一条短信带着
     * 4–8 位数字、且是刚到的"。审核层靠它把"界面写着验证码"和"验证码真的来了"
     * 区分开——前者多数只是登录页说明，后者才是要防的那一刻。
     */
    const val OP_SMS_CODE = "smsCode"

    // request parameters
    const val X = "x"
    const val Y = "y"
    const val X2 = "x2"
    const val Y2 = "y2"
    const val MS = "ms"
    const val TEXT = "text"
    const val KEY = "key"
    const val PKG = "pkg"
    const val OUT = "out"
    const val OFFSET = "offset"
    const val LEN = "len"
    const val CHUNK_DATA = "chunk"

    // ---- 1.6.3 扩展参数 ----
    const val DIRECTION = "direction"
    const val DISTANCE = "distance"
    const val ORIENTATION = "orientation"
    const val LEVEL = "level"
    const val ON = "on"
    const val STREAM = "stream"
    const val FEATURE = "feature"
    const val INCLUDING_SYSTEM = "includingSystem"
    const val LIMIT = "limit"

    // response keys
    const val OK = "ok"
    const val ERROR = "error"
    const val UID = "uid"
    const val WIDTH = "width"
    const val HEIGHT = "height"
    const val PATH = "path"
    const val BYTES = "bytes"
    const val N = "n"
    const val LABELS = "labels"
    const val PACKAGES = "packages"

    // ---- 1.6.3 扩展响应 ----
    const val TEXTS = "texts"
    const val JSON = "json"
    const val VALUE = "value"
    const val LEVELS = "levels"

    // ---- 危险操作审核响应 ----
    /** 是否检测到「带数字的短信」（验证码真的到了）。 */
    const val FOUND = "found"
    /** 命中详情（发送方/应用名 + 片段），供确认框展示，不含验证码原文。 */
    const val DETAIL = "detail"

    // error codes
    const val E_IDENTITY = "identityChanged"
    const val E_DENIED = "denied"
    const val E_UNSUPPORTED = "unsupported"
    const val E_FAILED = "failed"
}

/**
 * Hand-written Binder interface. [Stub] lives in the shell-uid UserService
 * process, [Proxy] in the app process; both sides carry exactly the same Bundle.
 */
interface IPhoneControl : IInterface {
    fun execute(request: Bundle): Bundle

    abstract class Stub : Binder(), IPhoneControl {
        init {
            attachInterface(this, DESCRIPTOR)
        }

        override fun asBinder(): IBinder = this

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == TXN_EXECUTE) {
                data.enforceInterface(DESCRIPTOR)
                val request = data.readBundle(javaClass.classLoader) ?: Bundle()
                val result = try {
                    execute(request)
                } catch (t: Throwable) {
                    Bundle().apply {
                        putBoolean(PhoneProto.OK, false)
                        putString(PhoneProto.ERROR, PhoneProto.E_FAILED)
                        putString(PhoneProto.REASON, t.javaClass.simpleName)
                    }
                }
                reply?.writeNoException()
                reply?.writeBundle(result)
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }

        private class Proxy(private val remote: IBinder) : IPhoneControl {
            override fun asBinder(): IBinder = remote

            override fun execute(request: Bundle): Bundle {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    data.writeBundle(request)
                    remote.transact(TXN_EXECUTE, data, reply, 0)
                    reply.readException()
                    return reply.readBundle(javaClass.classLoader) ?: Bundle()
                } finally {
                    reply.recycle()
                    data.recycle()
                }
            }
        }

        companion object {
            fun asInterface(binder: IBinder?): IPhoneControl? {
                if (binder == null) return null
                val local = binder.queryLocalInterface(DESCRIPTOR)
                return if (local is IPhoneControl) local else Proxy(binder)
            }
        }
    }

    companion object {
        const val DESCRIPTOR = "dev.dsh.remote.phone.IPhoneControl"
        const val TXN_EXECUTE = IBinder.FIRST_CALL_TRANSACTION
    }
}
