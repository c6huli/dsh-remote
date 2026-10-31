package dev.dsh.remote.net

import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Raw TCP forwarder on a loopback port, pointed at the host.
 *
 * The embedded Web panels must load the host page from a **loopback hostname**.
 * dsh's web client decides whether it is running against the local authority
 * from `location.hostname` alone:
 *
 *     isLoopback: isLoopbackHostname(pageLocation.hostname)
 *     // localhost, [::1], 127.0.0.0/8 → true
 *
 * When it is false, dsh deliberately withholds the settings surfaces and the
 * settings pages answer "settings are unavailable in this browser". Loading the
 * host over the tailnet therefore gives a panel that renders but cannot open
 * 设置 → 模型.
 *
 * The previous solution was an in-app HTTP proxy on 127.0.0.1:3086. It gave the
 * page a loopback origin, but it could only carry plain HTTP: a WebSocket
 * upgrade through it was proxied as a normal request and hung, which is what
 * made the settings panel sit on "加载超时（60 秒）". A TCP tunnel forwards bytes
 * without understanding them, so HTTP and WebSocket both work.
 */
class LoopbackTunnel(
    private val targetHost: String,
    private val targetPort: Int,
    private val preferredPort: Int = 3080,
) {
    @Volatile
    private var server: ServerSocket? = null

    /** The port actually bound, or -1 before [start]. */
    @Volatile
    var port: Int = -1
        private set

    /** Bind the listener and return the loopback port, or throw. */
    fun start(): Int {
        server?.let { if (!it.isClosed) return port }
        val bound = bind(preferredPort) ?: bind(0)
            ?: throw java.io.IOException("no free loopback port for the tunnel")
        server = bound
        port = bound.localPort
        thread(isDaemon = true, name = "dsh-tunnel-accept") {
            while (!bound.isClosed) {
                val client = try {
                    bound.accept()
                } catch (_: Throwable) {
                    break
                }
                thread(isDaemon = true, name = "dsh-tunnel-conn") { pipe(client) }
            }
        }
        return port
    }

    fun stop() {
        try { server?.close() } catch (_: Throwable) {}
        server = null
        port = -1
    }

    private fun bind(candidate: Int): ServerSocket? = try {
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress("127.0.0.1", candidate))
        }
    } catch (_: Throwable) {
        null
    }

    /** One client connection: forward both directions until either side closes. */
    private fun pipe(client: Socket) {
        var upstream: Socket? = null
        try {
            upstream = Socket().apply {
                tcpNoDelay = true
                connect(InetSocketAddress(targetHost, targetPort), CONNECT_TIMEOUT_MS)
            }
            val back = thread(isDaemon = true, name = "dsh-tunnel-up") { copy(client, upstream) }
            copy(upstream, client)
            back.join(JOIN_TIMEOUT_MS)
        } catch (_: Throwable) {
            // A dead tunnel must never take the UI down; the caller falls back.
        } finally {
            try { client.close() } catch (_: Throwable) {}
            try { upstream?.close() } catch (_: Throwable) {}
        }
    }

    private fun copy(from: Socket, to: Socket) {
        val buffer = ByteArray(16 * 1024)
        try {
            val input: InputStream = from.getInputStream()
            val output = to.getOutputStream()
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                output.flush()
            }
        } catch (_: Throwable) {
        } finally {
            // Half-close so the peer sees EOF instead of waiting for a timeout.
            try { to.shutdownOutput() } catch (_: Throwable) {}
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val JOIN_TIMEOUT_MS = 30_000L
    }
}
