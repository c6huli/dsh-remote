package dev.dsh.remote.phone

import dev.dsh.remote.data.DshApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [PhonePlanner] backed by the host's own model.
 *
 * It keeps one dedicated "planner" session alive (created lazily, reused after
 * that) so each instruction is a single prompt rather than a fresh session. The
 * session lives on the desktop dsh the app is already connected to — the phone
 * never needs its own model.
 */
class HostPhonePlanner(
    private val apiProvider: () -> DshApi?,
) : PhonePlanner {

    /** Session used purely for intent analysis; created on first use. */
    @Volatile
    private var plannerSessionId: String? = null

    /** Serialises session creation so two quick instructions cannot race into
     *  two planner sessions (one of which would then be orphaned on the host). */
    private val sessionLock = Mutex()

    @Volatile
    var screenWidth: Int = 1080

    @Volatile
    var screenHeight: Int = 2400

    override suspend fun plan(text: String): String? {
        val api = apiProvider() ?: return null
        val prompt = PhoneIntentParser.buildPrompt(text, screenWidth, screenHeight)
        val first = plannerSessionId
        if (first != null) {
            val reply = ask(api, first, prompt)
            if (reply != null) return reply
            // The cached session may be gone (host restart / session deleted).
            // Drop it and retry once with a fresh one.
            plannerSessionId = null
        }
        val fresh = ensureSession(api) ?: return null
        return ask(api, fresh, prompt)
    }

    /**
     * One prompt/answer round.
     *
     * Cancellation is rethrown rather than swallowed: an emergency stop during
     * "分析意图" must end the request, not be reported as "model unavailable".
     */
    private suspend fun ask(api: DshApi, sessionId: String, prompt: String): String? =
        try {
            api.planPhoneIntent(sessionId, prompt)
        } catch (c: kotlin.coroutines.cancellation.CancellationException) {
            throw c
        } catch (_: Throwable) {
            null
        }

    private suspend fun ensureSession(api: DshApi): String? = sessionLock.withLock {
        plannerSessionId?.let { return@withLock it }
        val id = try {
            api.sessionCreate().sessionId
        } catch (c: kotlin.coroutines.cancellation.CancellationException) {
            throw c
        } catch (_: Throwable) {
            return@withLock null
        }
        plannerSessionId = id
        id
    }

    /** Forget the planner session (e.g. after the host restarted). */
    fun reset() {
        plannerSessionId = null
    }
}
