package dev.dsh.remote.net

import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient

/**
 * Trusts the self-signed certificate served by the local Caddy HTTPS proxy.
 * This is acceptable for a personal tool whose server address is explicitly
 * configured and reached over a private Tailscale network.
 */
object TrustAll {
    val trustManager: X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    val sslSocketFactory: SSLSocketFactory by lazy {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        ctx.socketFactory
    }

    /**
     * Pre-build the SSL context on a background thread.
     *
     * `SSLContext.init` with a fresh [SecureRandom] can take hundreds of ms —
     * occasionally seconds on a cold boot with a starved entropy pool. It used to
     * run synchronously on the main thread (viewModelScope launches inline up to
     * the first suspension, and the first thing the startup path did was build a
     * client), which left the window blank with no frame drawn: the "sometimes
     * white/black screen on launch".
     *
     * Warms it once, off the main thread, so the first real call is a cache hit.
     */
    fun warmUpAsync() {
        if (warmed) return
        warmed = true
        runCatching {
            Thread({
                runCatching { sslSocketFactory }
            }, "dsh-tls-warmup").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }.start()
        }
    }

    @Volatile
    private var warmed = false

    /**
     * Keep OkHttp's default `okhttp/x.y` User-Agent on purpose.
     *
     * Caddy routes anything matching `okhttp` to the protocol bridge on :3084,
     * and this app speaks the legacy dotted RPC names (`session.list`,
     * `workspace.list` …) that the bridge translates to the host's native
     * `ns/method` form. Presenting a browser UA here would send those calls
     * straight to the host, which answers every one of them with
     * "Remote payload must contain exactly one plain-object args field".
     */
    fun client(): OkHttpClient = OkHttpClient.Builder()
        .sslSocketFactory(sslSocketFactory, trustManager)
        .hostnameVerifier { _, _ -> true }
        // Fail over to the next address quickly instead of hanging on a dead one.
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
}
