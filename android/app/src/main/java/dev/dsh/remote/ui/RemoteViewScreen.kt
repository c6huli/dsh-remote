package dev.dsh.remote.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.dsh.remote.ui.icons.DshIcon
import dev.dsh.remote.ui.icons.DshIcons
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Remote-rendered Web UI.
 *
 * The host renders the page in headless Edge and streams JPEG frames; this screen
 * only decodes images and sends taps back. That keeps it working on devices whose
 * WebView is missing/outdated, and the phone never downloads the frontend bundle.
 */
@Composable
fun RemoteViewScreen(vm: AppViewModel, onClose: () -> Unit) {
    val serverUrl by vm.serverUrl.collectAsState()
    val activeBaseUrl by vm.activeBaseUrl.collectAsState()
    val http = (activeBaseUrl ?: serverUrl).ifBlank { "http://127.0.0.1:8080" }.trimEnd('/')
    val wsUrl = http
        .replaceFirst("https://", "wss://")
        .replaceFirst("http://", "ws://") + "/remote-view/ws"

    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var status by remember { mutableStateOf("连接中…") }
    var connected by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    val ws = remember { java.util.concurrent.atomic.AtomicReference<WebSocket?>(null) }

    fun send(json: String) {
        try { ws.get()?.send(json) } catch (_: Throwable) {}
    }

    /** Minimal JSON string escaping for the text payload. */
    fun jsonEscape(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "")

    fun sendText(text: String) {
        if (text.isEmpty()) return
        send("{\"type\":\"type\",\"text\":\"${jsonEscape(text)}\"}")
    }

    DisposableEffect(wsUrl) {
        val client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
        val socket = client.newWebSocket(
            Request.Builder().url(wsUrl).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    status = "已连接，等待首帧…"
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val o = Json.parseToJsonElement(text).jsonObject
                        when (o["type"]?.jsonPrimitive?.content) {
                            "ready" -> {
                                connected = true
                                status = "已连接"
                            }
                            "frame" -> {
                                val data = o["data"]?.jsonPrimitive?.content ?: return
                                val bytes = Base64.decode(data, Base64.DEFAULT)
                                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
                                bitmap = bmp
                                connected = true
                                status = "已连接"
                            }
                            "error" -> status = o["message"]?.jsonPrimitive?.content ?: "服务端错误"
                        }
                    } catch (_: Throwable) {
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    connected = false
                    status = "连接失败: ${t.message ?: t.javaClass.simpleName}"
                }
            },
        )
        ws.set(socket)
        onDispose {
            try { socket.close(1000, "bye") } catch (_: Throwable) {}
            ws.set(null)
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // ---- header ----
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                DshIcon(DshIcons.ChevronLeft, tint = MaterialTheme.colorScheme.onSurface, size = 22.dp, contentDescription = "返回")
            }
            Column(Modifier.weight(1f)) {
                Text("远程界面", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!connected) {
                        CircularProgressIndicator(modifier = Modifier.size(10.dp), strokeWidth = 1.5.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        status,
                        color = if (connected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            TextButton(onClick = { send("{\"type\":\"reload\"}") }) { Text("刷新") }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))

        // ---- streamed page ----
        val img = bitmap
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background),
        ) {
            if (img == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "正在连接远程渲染…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val cw = constraints.maxWidth.toFloat()
                val ch = constraints.maxHeight.toFloat()
                val scale = minOf(cw / img.width.toFloat(), ch / img.height.toFloat())
                val drawW = img.width * scale
                val drawH = img.height * scale
                val offX = (cw - drawW) / 2f
                val offY = (ch - drawH) / 2f

                fun toPage(x: Float, y: Float): Pair<Int, Int>? {
                    val px = (x - offX) / scale
                    val py = (y - offY) / scale
                    if (px < 0f || py < 0f || px > img.width || py > img.height) return null
                    return px.toInt() to py.toInt()
                }

                Image(
                    bitmap = img.asImageBitmap(),
                    contentDescription = "远程页面",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
                // Taps → click; drags → wheel (scrolling the real page).
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(img) {
                            detectTapGestures { offset ->
                                val p = toPage(offset.x, offset.y) ?: return@detectTapGestures
                                send("{\"type\":\"click\",\"x\":${p.first},\"y\":${p.second}}")
                            }
                        }
                        .pointerInput(img) {
                            var acc = 0f
                            detectDragGestures(
                                onDragEnd = {
                                    if (acc != 0f) {
                                        send("{\"type\":\"wheel\",\"dy\":${acc.toInt()}}")
                                        acc = 0f
                                    }
                                },
                            ) { _, drag ->
                                acc += drag.y
                            }
                        },
                )
            }
        }

        // ---- text input for URL bars / search boxes ----
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入文字到页面…", style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                keyboardActions = KeyboardActions(onDone = {
                    sendText(input)
                    input = ""
                }),
                textStyle = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.width(6.dp))
            TextButton(onClick = {
                sendText(input)
                input = ""
            }) { Text("输入") }
            TextButton(onClick = { send("{\"type\":\"key\",\"key\":\"Enter\"}") }) { Text("回车") }
        }
    }
}