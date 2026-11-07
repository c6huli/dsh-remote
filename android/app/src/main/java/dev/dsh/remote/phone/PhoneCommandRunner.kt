package dev.dsh.remote.phone

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Turns a sentence into the model's planned reply.
 *
 * Implemented on top of the host session channel; kept as an interface so the
 * runner has no dependency on networking and can be exercised with a fake.
 */
interface PhonePlanner {
    /** Ask the model what to do; return its raw reply, or null when unavailable. */
    suspend fun plan(text: String): String?
}

/**
 * Background executor for typed phone instructions.
 *
 * The control page is just a text box: it hands a line to [run], which parses it
 * into [PhoneStep]s and performs them one after another on the shell side. The
 * job is cancellable at any point (the floating stop button calls [stop]), and
 * every step is checked against the coroutine's active flag so an emergency stop
 * takes effect before the next privileged call rather than after it.
 */
class PhoneCommandRunner(
    private val context: Context,
    private val scope: CoroutineScope,
    /** Host-side intent planner; null ⇒ deterministic parsing only. */
    private val planner: PhonePlanner? = null,
    /** Existing engine handle to share (avoids a second Shizuku binding). */
    sharedControl: PhoneControl? = null,
) {
    private val control = sharedControl ?: PhoneControl(context)

    /** One executed step, for the running log the page shows. */
    data class Entry(
        val text: String,
        val ok: Boolean,
        val error: String? = null,
        val startedAt: Long = System.currentTimeMillis(),
    )

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** Newest-last log of what actually happened. */
    private val _log = MutableStateFlow<List<Entry>>(emptyList())
    val log: StateFlow<List<Entry>> = _log.asStateFlow()

    /** True while the model is still deciding what to do. */
    private val _planning = MutableStateFlow(false)
    val planning: StateFlow<Boolean> = _planning.asStateFlow()

    /** Which engine produced the current plan, for an honest UI label. */
    private val _plannerUsed = MutableStateFlow("")
    val plannerUsed: StateFlow<String> = _plannerUsed.asStateFlow()

    /**
     * Parked run waiting for a human "yes".
     *
     * Non-null means the executor stopped at a dangerous step and will not
     * continue until [confirmRisk] or [stop] is called. Keeping the pending
     * action here (rather than inside the coroutine) lets the UI render exactly
     * what would happen next.
     */
    data class RiskPrompt(
        val reason: String,
        val hits: List<PhoneRiskReview.Hit>,
        val nextAction: String,
        /** Steps already done, so the run can resume from the right place. */
        val remaining: List<PhoneStep>,
        val index: Int,
    )

    private val _risk = MutableStateFlow<RiskPrompt?>(null)
    val risk: StateFlow<RiskPrompt?> = _risk.asStateFlow()

    /** Resume gate: completed by [confirmRisk], failed by [stop]. */
    private var riskGate: kotlinx.coroutines.CompletableDeferred<Boolean>? = null

    /** Bumped per run so a stale confirmation cannot resume a newer run. */
    @Volatile
    private var riskGeneration = -1

    /**
     * True once the person has accepted "this screen cannot be inspected".
     *
     * Without it, a single instruction becomes a dozen identical dialogs on any
     * app that blocks uiautomator. Reset per run.
     */
    @Volatile
    private var unreadableScreenAccepted = false

    /** Cached answer to "did a code SMS arrive", probed once per run. */
    @Volatile
    private var smsCodeSeen = false

    /** Redacted sender/hint for the dialog; never the code itself. */
    @Volatile
    private var smsCodeDetail = ""

    /** Exposed so the overlay holder can dismiss the floating stop button. */
    fun appContext(): Context = context.applicationContext

    private var job: Job? = null

    /**
     * Generation counter for the in-flight instruction.
     *
     * A stop followed immediately by a new instruction must not let the old
     * coroutine's `finally` clear the new one's `running` flag, and an old
     * cancellation must not be reported as a step failure. Every write of
     * "I am finished" is tagged with the generation it belongs to.
     */
    private val generation = java.util.concurrent.atomic.AtomicInteger(0)

    /** Deterministic preview for the "将要执行" list before the model is asked. */
    fun preview(input: String): List<PhoneStep> = PhoneCommand.parse(input)

    /**
     * Execute one typed instruction in the background.
     *
     * Intent is resolved by the model when a planner is configured; the local
     * rule parser is the fallback. Returns false when another instruction is
     * still running.
     */
    fun run(input: String): Boolean {
        if (_running.value) return false
        if (input.isBlank()) return false
        val gen = generation.incrementAndGet()
        unreadableScreenAccepted = false
        smsCodeSeen = false
        smsCodeDetail = ""
        _running.value = true
        job = scope.launch {
            try {
                val steps = resolveSteps(input)
                if (!isCurrent(gen)) return@launch
                if (steps.isNullOrEmpty()) {
                    append(Entry("没听懂这句话", ok = false, error = "模型没有给出可执行的动作，换个说法试试"))
                    return@launch
                }
                // Intent review first: a statement like "帮我付了这笔" must not
                // reach the device at all before a human agrees.
                //
                // Verification is evidence-only: an SMS that really arrived, or a
                // code-shaped value in the plan. A sentence that merely mentions
                // 验证码 no longer stops the run.
                //
                // Probe the shade only when this run could actually consume a
                // code — otherwise every plain tap would pay an extra dumpsys.
                val couldUseCode = steps.any { it is PhoneStep.TypeText } || mentionsCode(input)
                val evidence = verifyEvidenceFor(steps, probeSms = couldUseCode)
                val intent = PhoneRiskReview.reviewInstruction(input, steps, evidence)
                if (intent.flagged) {
                    val go = pauseForRisk(
                        RiskPrompt(
                            reason = "这句话涉及：" + intent.reason,
                            hits = intent.hits,
                            nextAction = "整条指令（${steps.size} 个动作）",
                            remaining = steps,
                            index = 0,
                        ),
                        gen,
                    )
                    if (!go) return@launch
                }
                executeAll(steps, gen)
            } finally {
                // Only the newest run may clear the shared flags; otherwise a
                // cancelled run would blank out the one that replaced it.
                if (isCurrent(gen)) {
                    _planning.value = false
                    _running.value = false
                    // Nothing left to stop — take the floating button away
                    // instead of leaving a dead control on screen.
                    PhoneCommandRunnerHolder.hideOverlay()
                }
            }
        }
        return true
    }

    private fun isCurrent(gen: Int): Boolean = generation.get() == gen

    /**
     * Ask the model first; fall back to the rule parser only when the model
     * could not be reached or produced nothing parseable.
     *
     * A deliberate `[]` from the model means "this is not a phone action" — in
     * that case falling through to regex guessing is wrong (it would happily tap
     * random coordinates at a sentence it did not understand), so an empty plan
     * is returned and reported instead.
     */
    private suspend fun resolveSteps(input: String): List<PhoneStep>? {
        val p = planner
        if (p != null) {
            _planning.value = true
            try {
                val reply = p.plan(input)
                _planning.value = false
                val parsed = reply?.let { PhoneIntentParser.parseReply(it) }
                when {
                    parsed == null -> Unit                 // unparseable → fall back
                    parsed.isEmpty() -> {
                        _plannerUsed.value = "模型"
                        return emptyList()                 // model says: no action
                    }
                    else -> {
                        _plannerUsed.value = "模型"
                        return parsed
                    }
                }
            } catch (c: kotlin.coroutines.cancellation.CancellationException) {
                // A stop is not a planner failure — let it propagate so the job
                // really ends instead of falling through to the rule parser.
                _planning.value = false
                throw c
            } catch (t: Throwable) {
                _planning.value = false
            }
        }
        _plannerUsed.value = "本地规则"
        return PhoneCommand.parse(input)
    }

    private suspend fun executeAll(steps: List<PhoneStep>, gen: Int) {
        for ((index, step) in steps.withIndex()) {
            if (!isCurrent(gen)) break
            val label = describe(step, index + 1, steps.size)
            if (step is PhoneStep.Unknown) {
                append(Entry(label, ok = false, error = step.hint))
                continue
            }
            // Scene review: coordinates mean nothing on their own, so look at
            // the screen the tap is about to land on. Only actions that actually
            // commit something need it — navigation keys cannot buy anything.
            if (needsSceneReview(step)) {
                // Reuse the run-level SMS answer; only the typed-code part is
                // re-evaluated per step (a code may only appear later in the plan).
                val stepEvidence = verifyEvidenceFor(listOf(step))
                val gate = reviewCurrentScreen(step, steps, index, label, gen, stepEvidence)
                if (!gate) return
            }
            _log.value = _log.value + Entry(label + " …", ok = true)
            val started = System.currentTimeMillis()
            try {
                execute(step)
                if (!isCurrent(gen)) break
                replaceLast(Entry(label, ok = true))
            } catch (c: kotlin.coroutines.cancellation.CancellationException) {
                // Stopped mid-step: drop the optimistic "…" row rather than
                // reporting the user's own stop as a failure.
                removeLast()
                throw c
            } catch (t: Throwable) {
                if (!isCurrent(gen)) break
                replaceLast(Entry(label, ok = false, error = t.message ?: "操作失败"))
            }
            if (step is PhoneStep.Launch) delay(700)
            else if (System.currentTimeMillis() - started < 60) delay(60)
        }
    }

    /** Actions that can commit money/identity only; pure navigation is exempt. */
    private fun needsSceneReview(step: PhoneStep): Boolean = when (step) {
        is PhoneStep.Tap, is PhoneStep.DoubleTap, is PhoneStep.LongPress,
        is PhoneStep.TypeText, is PhoneStep.Swipe -> true
        else -> false
    }

    /**
     * Cheap text test: does this instruction talk about a verification code?
     *
     * Used only to decide whether the SMS probe is worth its round trip. The
     * probe itself is what decides risk; this must never be the sole reason a
     * prompt appears.
     */
    private fun mentionsCode(text: String): Boolean {
        val t = text.lowercase()
        return CODE_WORDS.any { t.contains(it) }
    }

    /**
     * Gather the two pieces of hard evidence the verification rule needs.
     *
     * - Did an SMS with digits just arrive? (shade inspection, redacted)
     * - Is a code-shaped secret being typed in these steps?
     *
     * Both are *events*, not labels, which is why a page that merely says
     * "验证码" no longer produces a prompt.
     *
     * The SMS probe costs one dumpsys round trip, so it runs once per
     * instruction ([probeSms]) and later steps reuse the cached answer.
     */
    private suspend fun verifyEvidenceFor(
        steps: List<PhoneStep>,
        probeSms: Boolean = false,
    ): PhoneRiskReview.VerifyEvidence {
        if (probeSms) {
            val sms = try {
                control.smsCode()
            } catch (c: kotlin.coroutines.cancellation.CancellationException) {
                throw c
            } catch (_: Throwable) {
                null
            }
            smsCodeSeen = sms?.found == true
            smsCodeDetail = sms?.detail.orEmpty()
        }
        val typedCode = steps.any {
            it is PhoneStep.TypeText && PhoneRiskReview.looksLikeCode(it.text)
        }
        return PhoneRiskReview.VerifyEvidence(
            smsFound = smsCodeSeen,
            smsDetail = smsCodeDetail,
            secretTyped = typedCode,
        )
    }

    /**
     * Read the screen and, when it looks financial/identity-related, park the
     * run until the person confirms. Returns false when the run must stop.
     */
    private suspend fun reviewCurrentScreen(
        step: PhoneStep,
        steps: List<PhoneStep>,
        index: Int,
        label: String,
        gen: Int,
        evidence: PhoneRiskReview.VerifyEvidence,
    ): Boolean {
        val texts = try {
            control.screenText()
        } catch (c: kotlin.coroutines.cancellation.CancellationException) {
            throw c
        } catch (_: Throwable) {
            null
        }
        // null = the engine could not answer at all; empty = the screen exists
        // but exposes no text (app blocks uiautomator). Both fail closed, with
        // different wording.
        val unreadable = texts == null || texts.isEmpty()
        val verdict = PhoneRiskReview.reviewScreen(
            texts ?: emptyList(),
            readable = !unreadable,
            evidence = evidence,
        )
        if (!verdict.needsConfirmation) return true
        // The "cannot inspect this app" case is confirmed once per run, not once
        // per action: the same answer would otherwise pop up for every tap.
        val unreadableOnly = verdict.hits.isEmpty()
        if (unreadableOnly && unreadableScreenAccepted) return true
        append(Entry("⚠️ $label 需要确认", ok = false, error = verdict.reason))
        val ok = pauseForRisk(
            RiskPrompt(
                reason = verdict.reason,
                hits = verdict.hits,
                nextAction = label,
                remaining = steps,
                index = index,
            ),
            gen,
        )
        if (ok && unreadableOnly) unreadableScreenAccepted = true
        return ok
    }

    /**
     * Suspend until the person answers. Returns true to continue.
     *
     * A stop while parked cancels the awaiting coroutine, so the gate resolves
     * as failure and the caller returns without executing anything else.
     */
    private suspend fun pauseForRisk(prompt: RiskPrompt, gen: Int): Boolean {
        if (!isCurrent(gen)) return false
        riskGeneration = gen
        _risk.value = prompt
        val gate = kotlinx.coroutines.CompletableDeferred<Boolean>()
        riskGate = gate
        val approved = try {
            gate.await()
        } catch (c: kotlin.coroutines.cancellation.CancellationException) {
            _risk.value = null
            throw c
        } finally {
            _risk.value = null
            if (riskGate === gate) riskGate = null
        }
        if (approved) {
            append(Entry("已确认：${prompt.nextAction}", ok = true))
        } else {
            append(Entry("已取消危险操作", ok = false, error = prompt.reason))
        }
        return approved
    }

    /** Person tapped "确认执行" on the risk dialog. */
    fun confirmRisk() {
        // Only a gate belonging to the newest run may be opened; a stale dialog
        // from a cancelled run must never green-light whatever runs next.
        if (riskGeneration != generation.get()) return
        riskGate?.complete(true)
    }

    /** Person tapped "取消" — abort the run at the parked step. */
    fun rejectRisk() {
        riskGate?.complete(false)
    }

    /** Emergency stop: cancels the in-flight instruction after the current call. */
    fun stop() {
        val wasRunning = _running.value
        val wasPlanning = _planning.value
        generation.incrementAndGet()
        // A run parked on a risk prompt must not stay suspended: release the
        // gate as "no" so its coroutine finishes instead of leaking.
        riskGate?.complete(false)
        riskGate = null
        _risk.value = null
        job?.cancel()
        job = null
        _running.value = false
        _planning.value = false
        // Only mention the stop when something was actually in flight, so an
        // idle stop (page button + overlay both firing) does not scribble two
        // fake "stopped" lines into the log.
        if (wasRunning || wasPlanning) {
            append(Entry("已紧急停止", ok = false, error = "用户中止"))
        }
    }

    fun clearLog() {
        _log.value = emptyList()
    }

    private fun removeLast() {
        val list = _log.value
        if (list.isNotEmpty()) _log.value = list.dropLast(1)
    }

    private suspend fun execute(step: PhoneStep) {
        when (step) {
            is PhoneStep.Launch -> control.launchApp(resolvePackage(step.query))
            is PhoneStep.Tap -> control.tap(step.x, step.y)
            is PhoneStep.LongPress -> control.longPress(step.x, step.y, step.ms)
            is PhoneStep.DoubleTap -> control.doubleTap(step.x, step.y)
            is PhoneStep.Swipe -> control.swipe(step.x, step.y, step.x2, step.y2, step.ms)
            is PhoneStep.Scroll -> control.scroll(step.direction, step.distance)
            is PhoneStep.Key -> control.key(step.name)
            is PhoneStep.HardKey -> control.hardKey(step.name)
            is PhoneStep.TypeText -> control.text(step.text)
            is PhoneStep.Brightness -> {
                val level = if (step.delta) {
                    (control.brightness(-1) + step.level).coerceIn(0, 255)
                } else step.level
                control.brightness(level)
            }
            is PhoneStep.Volume -> {
                val level = if (step.delta) {
                    (control.volume(step.stream, -1) + step.level).coerceIn(0, 100)
                } else step.level
                control.volume(step.stream, level)
            }
            is PhoneStep.Toggle -> control.toggle(step.feature, step.on)
            is PhoneStep.Wait -> delay(step.ms)
            is PhoneStep.Unknown -> Unit
        }
    }

    /** Map a typed app name ("微信", "bili", "com.tencent.mm") to a real package. */
    private suspend fun resolvePackage(query: String): String {
        val q = query.trim()
        if (q.isEmpty()) error("没写要打开哪个应用")
        // Exact package name: let the engine decide, no listing needed.
        if (q.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))) return q
        val needle = q.lowercase()
        val apps = control.apps()
        // 1) label contains / equals, 2) package contains.
        apps.firstOrNull { it.label.lowercase() == needle }?.let { return it.pkg }
        apps.firstOrNull { it.label.lowercase().contains(needle) }?.let { return it.pkg }
        apps.firstOrNull { it.pkg.lowercase().contains(needle) }?.let { return it.pkg }
        error("没有找到叫「$q」的应用")
    }

    private fun describe(step: PhoneStep, index: Int, total: Int): String {
        val what = when (step) {
            is PhoneStep.Launch -> "打开 ${step.query}"
            is PhoneStep.Tap -> "点 ${step.x},${step.y}"
            is PhoneStep.LongPress -> "长按 ${step.x},${step.y} ${step.ms}ms"
            is PhoneStep.DoubleTap -> "双击 ${step.x},${step.y}"
            is PhoneStep.Swipe -> "滑动 ${step.x},${step.y} → ${step.x2},${step.y2}"
            is PhoneStep.Scroll -> "滚动 ${step.direction}"
            is PhoneStep.Key -> "按键 ${step.name}"
            is PhoneStep.HardKey -> "物理键 ${step.name}"
            is PhoneStep.TypeText -> "输入「${step.text}」"
            is PhoneStep.Brightness ->
                if (step.delta) "亮度 ${if (step.level >= 0) "+" else ""}${step.level}" else "亮度 ${step.level}"
            is PhoneStep.Volume ->
                if (step.delta) "音量 ${if (step.level >= 0) "+" else ""}${step.level}" else "音量 ${step.level}"
            is PhoneStep.Toggle -> "${if (step.on) "打开" else "关闭"} ${step.feature}"
            is PhoneStep.Wait -> "等待 ${step.ms}ms"
            is PhoneStep.Unknown -> "无法识别「${step.text}」"
        }
        return if (total > 1) "[$index/$total] $what" else what
    }

    private fun append(entry: Entry) {
        _log.value = (_log.value + entry).takeLast(MAX_LOG)
    }

    private fun replaceLast(entry: Entry) {
        val list = _log.value
        if (list.isEmpty()) return append(entry)
        _log.value = list.dropLast(1) + entry
    }

    companion object {
        private const val MAX_LOG = 60

        /** Words that make an SMS probe worthwhile. */
        private val CODE_WORDS = listOf(
            "验证码", "校验码", "动态码", "短信", "otp", "code", "verif",
        )
    }
}
