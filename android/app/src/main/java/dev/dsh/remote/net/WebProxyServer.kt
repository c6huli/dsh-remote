package dev.dsh.remote.net

import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Serves the dsh Web UI from the phone.
 *
 * The WebView points at http://127.0.0.1:<port>, so the page and its bundle load
 * from local storage instead of being pulled over the tailnet every time. Only
 * `/api/...` (and anything else) is proxied to the host, where Caddy injects the
 * session cookie — so the app never has to reproduce that auth itself.
 *
 * `shouldInterceptRequest` cannot do this job: it never exposes a POST body, and
 * every dsh RPC is a POST.
 */
class WebProxyServer(
    private val upstream: String,
    private val cacheDir: File,
    private val client: OkHttpClient,
) : NanoHTTPD("127.0.0.1", PORT) {

    companion object {
        const val PORT = 3086

        private val STATIC_EXT = setOf("js", "css", "woff", "woff2", "ttf", "svg", "png", "jpg", "ico", "map")

        /**
         * Caddy routes by User-Agent: `okhttp` goes to the legacy protocol bridge,
         * anything else to the native 0.2.x host. The proxy must therefore look
         * like a browser, or every RPC would be translated for the wrong client.
         */
        const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"

        /**
         * Reshapes the host's Web app for a phone-sized viewport.
         *
         * The app only ever shows the settings surface, but the host renders it as
         * a modal dialog floating over its whole single-page app. On a phone the
         * page behind is useless, and rendering it at a wide layout viewport shrank
         * everything to an unreadable size with a large empty area around it. So:
         * stretch the dialog to the viewport, drop the scrim, and give the section
         * list a fixed narrow column — the panel then fills the screen the way a
         * native settings page would.
         */
        private const val PANEL_JS = """
<script>
/* marker: dsh-panel-fit */
(function () {
  var NAV_W = 118;

  function fit() {
    var panel = document.querySelector('[role=dialog]');
    if (!panel) return false;

    var overlay = panel.closest('[role=presentation]');
    if (overlay) {
      overlay.style.position = 'fixed';
      overlay.style.inset = '0';
      overlay.style.zIndex = '2147483000';
      overlay.style.display = 'block';
      overlay.style.padding = '0';
      overlay.style.background = 'transparent';
    }
    // The scrim is pointless once the panel covers everything.
    var scrim = overlay && overlay.firstElementChild;
    if (scrim && scrim !== panel) scrim.style.display = 'none';

    panel.style.position = 'absolute';
    panel.style.inset = '0';
    panel.style.width = '100%';
    panel.style.height = '100%';
    panel.style.maxWidth = 'none';
    panel.style.maxHeight = 'none';
    panel.style.margin = '0';
    panel.style.transform = 'none';
    panel.style.borderRadius = '0';
    panel.style.boxShadow = 'none';

    var nav = panel.querySelector(':scope > nav');
    if (nav) {
      nav.style.flex = '0 0 ' + NAV_W + 'px';
      nav.style.width = NAV_W + 'px';
      // Long section names must ellipsize, never wrap the row taller.
      nav.querySelectorAll('span').forEach(function (sp) {
        sp.style.fontSize = '12.5px';
        sp.style.whiteSpace = 'nowrap';
        sp.style.overflow = 'hidden';
        sp.style.textOverflow = 'ellipsis';
      });
      var title = nav.firstElementChild;
      if (title) title.style.paddingLeft = '8px';
    }
    var content = nav && nav.nextElementSibling;
    if (content) {
      content.style.flex = '1 1 auto';
      content.style.minWidth = '0';
    }
    // Floating plugin chrome has no place inside the panel view.
    document.querySelectorAll('.ofm_toasts,.dsh-sat-btn').forEach(function (n) {
      n.style.display = 'none';
    });
    return true;
  }

  var hadPanel = false;
  var reopenBudget = 8;
  var userClosed = false;

  /**
   * The dialog's own ✕ means "leave settings", the app's back arrow already does
   * that, so hand it to the host app. Without that handler (plain browser) the
   * click would leave a blank page behind, so the panel is restored instead.
   */
  function hookClose(panel) {
    if (panel.__dshCloseHooked) return;
    panel.__dshCloseHooked = true;
    panel.addEventListener('click', function (e) {
      var btn = e.target && e.target.closest ? e.target.closest('button') : null;
      if (!btn) return;
      var label = (btn.getAttribute('aria-label') || '') + ' ' + (btn.textContent || '');
      if (label.indexOf('关闭') < 0 && !/close/i.test(btn.className || '')) return;
      try {
        if (window.DshNative && window.DshNative.closeSettings) {
          userClosed = true;
          window.DshNative.closeSettings();
        }
      } catch (err) {}
    }, true);
  }

  function reopen() {
    // Some sections (the plugin market, for one) unmount the settings dialog when
    // they finish, which leaves this WebView showing the bare app — a blank screen
    // with nothing to tap. Put the sections list back instead.
    var rail = document.querySelector('button[aria-label="设置"]');
    if (!rail || reopenBudget <= 0) return;
    reopenBudget -= 1;
    rail.click();
  }

  function ensure() {
    var panel = document.querySelector('[role=dialog]');
    if (panel) {
      hadPanel = true;
      reopenBudget = 8;
      userClosed = false;
      hookClose(panel);
      fit();
      return;
    }
    if (hadPanel && !userClosed) reopen();
  }

  var pending = null;
  function schedule() {
    if (pending) return;
    pending = setTimeout(function () { pending = null; ensure(); }, 250);
  }

  if (!fit()) {
    var tries = 0;
    var t = setInterval(function () {
      if (fit() || ++tries > 150) clearInterval(t);
    }, 200);
  }
  // Re-apply when the panel is closed and reopened, or when a section swaps in.
  try {
    new MutationObserver(schedule).observe(document.documentElement, {
      childList: true, subtree: true
    });
  } catch (e) {}
})();
</script>
"""

        private fun isStatic(uri: String): Boolean {
            val path = uri.substringBefore('?')
            if (path == "/" || path.endsWith(".html")) return true
            val ext = path.substringAfterLast('.', "").lowercase()
            return ext in STATIC_EXT
        }

        /**
         * Only content-addressed bundle files are safe to cache: index.html carries
         * the per-session boot payload, so it must always come from the host.
         */
        private fun cacheable(uri: String): Boolean {
            val path = uri.substringBefore('?')
            if (path == "/" || path.endsWith(".html")) return false
            val ext = path.substringAfterLast('.', "").lowercase()
            return ext in STATIC_EXT
        }

        private fun mimeOf(uri: String): String = when (uri.substringAfterLast('.', "").lowercase()) {
            "js" -> "application/javascript"
            "css" -> "text/css"
            "html", "" -> "text/html"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg" -> "image/jpeg"
            "ico" -> "image/x-icon"
            "woff2" -> "font/woff2"
            "woff" -> "font/woff"
            "json" -> "application/json"
            else -> "application/octet-stream"
        }
    }

    /** Files the bundle consists of, for the "download" action. */
    @Volatile
    var cachedCount: Int = 0
        private set

    private fun cacheFile(uri: String): File {
        val clean = uri.substringBefore('?').trimStart('/').ifEmpty { "index.html" }
        return File(cacheDir, clean)
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        // 1. static bundle files come from disk when possible
        if (method == Method.GET && cacheable(uri)) {
            val f = cacheFile(uri)
            if (f.exists() && f.length() > 0) {
                return newChunkedResponse(Response.Status.OK, mimeOf(uri), f.inputStream())
            }
        }

        // 2. everything else is proxied to the host (Caddy adds the cookie)
        return proxy(session, uri, method)
    }

    private fun proxy(session: IHTTPSession, uri: String, method: Method): Response {
        val query = session.queryParameterString
        val target = upstream.trimEnd('/') + uri + if (query.isNullOrEmpty()) "" else "?$query"

        val bodyBytes: ByteArray? = if (method == Method.POST || method == Method.PUT) {
            try {
                val files = HashMap<String, String>()
                session.parseBody(files)
                files["postData"]?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
            } catch (_: Exception) {
                ByteArray(0)
            }
        } else null

        val builder = Request.Builder().url(target)
        for ((k, v) in session.headers) {
            when (k.lowercase()) {
                "host", "content-length", "connection", "accept-encoding", "user-agent" -> Unit
                else -> try { builder.header(k, v) } catch (_: Exception) {}
            }
        }
        // Ask for uncompressed bytes: we cache them verbatim.
        builder.header("accept-encoding", "identity")
        builder.header("user-agent", BROWSER_UA)
        builder.method(
            method.name,
            bodyBytes?.toRequestBody(session.headers["content-type"]?.toMediaType() ?: "application/json".toMediaType()),
        )

        return try {
            client.newCall(builder.build()).execute().use { resp ->
                val stream = resp.body?.byteStream() ?: throw IOException("empty body")
                val bytes = stream.readBytes()

                // Cache successful bundle GETs for the next open.
                if (method == Method.GET && cacheable(uri) && resp.isSuccessful && bytes.isNotEmpty()) {
                    val f = cacheFile(uri)
                    f.parentFile?.mkdirs()
                    try { f.writeBytes(bytes); cachedCount += 1 } catch (_: Exception) {}
                }

                val out = run {
                    // Inject the phone-oriented view into the shell HTML on the way through.
                    var outBytes = bytes
                    val isHtml = uri == "/" || uri.endsWith(".html") ||
                        (resp.header("content-type")?.contains("text/html") == true)
                    if (isHtml) {
                        try {
                            val html = String(bytes, Charsets.UTF_8)
                            if (!html.contains("dsh-panel-fit")) {
                                outBytes = html.replace("</body>", PANEL_JS + "</body>").toByteArray(Charsets.UTF_8)
                            }
                        } catch (_: Exception) {
                        }
                    }
                    newFixedLengthResponse(
                        Response.Status.lookup(resp.code) ?: Response.Status.OK,
                        resp.header("content-type") ?: "application/octet-stream",
                        outBytes.inputStream(),
                        outBytes.size.toLong(),
                    )
                }
                resp.header("cache-control")?.let { out.addHeader("cache-control", it) }
                out
            }
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "proxy: ${e.message}")
        }
    }

    /**
     * Fetch the whole bundle up front so later opens are instant.
     *
     * Parses the shell HTML for referenced scripts/styles and stores each one
     * through the same cache path the server reads.
     */
    suspend fun precache(): Int {
        val home = upstream.trimEnd('/') + "/"
        val html = client.newCall(
            Request.Builder().url(home).header("user-agent", BROWSER_UA).build()
        ).execute().use { resp ->
            if (!resp.isSuccessful) return 0
            resp.body?.string() ?: return 0
        }
        val idx = cacheFile("/index.html")
        idx.parentFile?.mkdirs()
        try { idx.writeText(html) } catch (_: Exception) {}

        val refs = Regex("""(?:src|href)\s*=\s*"([^"]+\.(?:js|css|woff2?|ttf|svg|png))"""")
            .findAll(html)
            .map { it.groupValues[1] }
            .distinct()
            .toList()

        var ok = 0
        for (ref in refs) {
            val path = when {
                ref.startsWith("http") -> continue
                ref.startsWith("/") -> ref
                else -> "/" + ref.removePrefix("./")
            }
            try {
                val url = upstream.trimEnd('/') + path
                client.newCall(
                    Request.Builder().url(url)
                        .header("accept-encoding", "identity")
                        .header("user-agent", BROWSER_UA)
                        .build()
                )
                    .execute().use { resp ->
                        if (!resp.isSuccessful) return@use
                        val bytes = resp.body?.bytes() ?: return@use
                        if (bytes.isEmpty()) return@use
                        val f = cacheFile(path)
                        f.parentFile?.mkdirs()
                        f.writeBytes(bytes)
                        ok += 1
                    }
            } catch (_: Exception) {
                // partial cache is still useful
            }
        }
        cachedCount = ok
        return ok
    }

    /** True once the asset bundle is on disk (index.html is never cached). */
    fun hasCache(): Boolean {
        val assets = File(cacheDir, "assets")
        return assets.isDirectory && (assets.listFiles()?.isNotEmpty() == true)
    }
}
