package dev.dsh.remote.phone

/**
 * Safety gate for phone actions that can cost money or leak identity.
 *
 * ## Why this exists
 *
 * Tapping coordinates is harmless in isolation — what is dangerous is *what is
 * on screen when the tap lands*. A model (or a typo) that says "点 540 2100" can
 * confirm a payment, submit an order, or fill a verification code, and none of
 * those look remarkable on the wire.
 *
 * Two checks, either can stop the run:
 *
 * 1. **Intent** — the words themselves ("支付", "下单", "身份证"…).
 * 2. **Scene** — the visible text of the screen at tap time (`uiautomator dump`).
 *
 * ## Verification codes are judged differently
 *
 * A login page saying "验证码" is not an event — it is furniture, and flagging it
 * made every ordinary sign-in stop for confirmation. So [Category.VERIFICATION]
 * is *contextual*: it never fires on keywords alone. It fires only on real
 * evidence:
 *
 * - an SMS with digits actually arrived ([VerifyEvidence.smsFound]), or
 * - a code-shaped secret is actually being typed ([VerifyEvidence.secretTyped]).
 *
 * Everything else keeps the broad keyword net, because for money those false
 * positives are the cheap side of the trade.
 */
object PhoneRiskReview {

    /** How bad a hit is; drives copy and whether confirmation is mandatory. */
    enum class Severity { INFO, MONEY, IDENTITY, SECRET }

    /**
     * One category of dangerous action.
     *
     * [keywords] are matched case-insensitively against the instruction and the
     * on-screen text — except for [contextual] categories, whose keywords are
     * only used for wording, never to trigger a prompt.
     */
    enum class Category(
        val severity: Severity,
        val label: String,
        val keywords: List<String>,
        /**
         * True when screen text alone must not raise a confirmation.
         *
         * Used by [VERIFICATION]: seeing the word is normal, seeing the code
         * arrive (or typing it) is the actual risk.
         */
        val contextual: Boolean = false,
    ) {
        PAYMENT(
            Severity.MONEY,
            "支付 / 付款",
            listOf(
                "支付", "付款", "付了", "付费", "付钱", "买单", "结了这单",
                "收银台", "去支付", "立即支付", "确认支付", "免密支付",
                "微信支付", "支付宝", "余额支付", "银行卡", "信用卡", "花呗", "白条",
                "pay", "payment", "checkout pay", "apple pay", "google pay",
            ),
        ),
        ORDER(
            Severity.MONEY,
            "下单 / 购买",
            listOf(
                "下单", "提交订单", "确认订单", "立即购买", "去结算", "结算", "购买",
                "买了", "买一个", "买这个", "加入购物车", "立即抢购", "抢购", "秒杀",
                "预售", "支付定金", "确认收货", "订单",
                "place order", "buy now", "checkout", "confirm order", "order it",
            ),
        ),
        TRANSFER(
            Severity.MONEY,
            "转账 / 红包",
            listOf(
                "转账", "汇款", "红包", "发红包", "打款", "打钱", "转钱", "转给",
                "提现", "充值", "还款", "借钱", "借款", "贷款", "投资", "理财买入",
                "基金买入", "买基金", "股票",
                "transfer", "withdraw", "top up",
            ),
        ),

        /**
         * SMS verification code.
         *
         * Contextual: keywords here exist for the dialog wording only. The
         * trigger comes from [VerifyEvidence] (an SMS arrived, or a secret is
         * being typed), never from the phrase "验证码" appearing on screen.
         */
        VERIFICATION(
            Severity.SECRET,
            "验证码 / 短信校验",
            listOf(
                "验证码", "校验码", "动态码", "短信验证", "获取验证码", "输入验证码",
                "六位数字", "6位数字", "四位数字", "4位数字",
                "verification code", "verify code", "otp", "2fa", "sms code",
            ),
            contextual = true,
        ),

        /**
         * Human-verification widgets that are *not* SMS codes.
         *
         * A slider/captcha blocks the flow until a person solves it, so screen
         * text is the right trigger — there is no "code arrives later" state.
         */
        CAPTCHA(
            Severity.SECRET,
            "图形 / 人机验证",
            listOf(
                "图形验证", "人机验证", "滑块验证", "拖动滑块", "点击图中", "安全验证",
                "captcha", "recaptcha", "i'm not a robot", "slide to verify",
            ),
        ),

        PASSWORD(
            Severity.SECRET,
            "密码 / 解锁",
            listOf(
                "密码", "支付密码", "登录密码", "解锁", "指纹", "面容", "人脸识别",
                "手势密码", "图案密码", "password", "passcode", "unlock",
                "fingerprint", "face id",
            ),
        ),
        IDENTITY(
            Severity.IDENTITY,
            "个人信息填写",
            listOf(
                "身份证", "实名认证", "真实姓名", "手机号", "手机号码", "联系电话",
                "银行卡号", "社保卡", "护照", "户口", "家庭住址", "收货地址",
                "详细地址", "紧急联系人", "有效期", "安全码", "工号", "邮箱",
                "cvv",
                "id card", "social security", "passport", "billing address",
                "card number", "phone number", "email address",
            ),
        ),
        ACCOUNT_SECURITY(
            Severity.IDENTITY,
            "账号安全设置",
            listOf(
                "修改密码", "重置密码", "绑定手机", "解绑手机", "注销账号", "注销",
                "实名", "更换绑定", "授权登录", "免密", "自动扣费", "连续包月",
                "订阅扣款", "取消订阅",
                "change password", "reset password", "delete account",
                "auto renew", "subscription",
            ),
        ),
        INSTALL(
            Severity.IDENTITY,
            "安装 / 卸载 / 授权",
            listOf(
                "安装", "卸载", "授予权限", "允许访问", "未知来源", "设备管理员",
                "install", "uninstall", "grant permission", "device admin",
            ),
        ),
    }

    /** A single matched category together with the evidence that triggered it. */
    data class Hit(
        val category: Category,
        val keyword: String,
        val source: Source,
    ) {
        enum class Source { INSTRUCTION, SCREEN }
    }

    /**
     * Hard evidence that a verification code is in play.
     *
     * Both fields describe things that actually happened, not things the UI
     * merely mentions — that is the whole point of the narrowed rule.
     */
    data class VerifyEvidence(
        /** An SMS containing a 4–8 digit code recently arrived. */
        val smsFound: Boolean = false,
        /** Redacted sender/hint for the dialog, never the code itself. */
        val smsDetail: String = "",
        /** A code-shaped secret is being typed right now. */
        val secretTyped: Boolean = false,
    )

    /** Result of reviewing one instruction (before executing anything). */
    data class Verdict(
        val hits: List<Hit>,
        /** True when the run must stop and wait for an explicit confirmation. */
        val needsConfirmation: Boolean,
        /** Human-readable reason; empty when nothing was flagged. */
        val reason: String,
    ) {
        val flagged: Boolean get() = hits.isNotEmpty()
    }

    /** 4–8 ASCII digits with nothing else — the canonical OTP/passcode shape. */
    private val OTA_SHAPE = Regex("^\\s*\\d{4,8}\\s*$")

    /** True when [value] looks like a verification code / one-time secret. */
    fun looksLikeCode(value: String): Boolean = OTA_SHAPE.matches(value.trim())

    /**
     * Review the words of an instruction.
     *
     * Verification is judged from [evidence] only; every other category keeps
     * the keyword net.
     */
    fun reviewInstruction(
        instruction: String,
        steps: List<PhoneStep>,
        evidence: VerifyEvidence = VerifyEvidence(),
    ): Verdict {
        val haystack = buildString {
            append(instruction).append('\n')
            for (s in steps) {
                when (s) {
                    is PhoneStep.Launch -> append(s.query).append('\n')
                    is PhoneStep.Toggle -> append(s.feature).append('\n')
                    // Typed text is NOT appended here: a code is handled by the
                    // evidence path so it can be reported as "验证码正在填入"
                    // instead of as an incidental keyword hit.
                    else -> Unit
                }
            }
        }
        val hits = matchText(haystack, Hit.Source.INSTRUCTION).toMutableList()
        // The steps themselves are the strongest evidence available here, so the
        // typed-code check is derived locally instead of trusting the caller to
        // have filled it in.
        val typedInSteps = steps.any { it is PhoneStep.TypeText && looksLikeCode(it.text) }
        val effective = evidence.copy(secretTyped = evidence.secretTyped || typedInSteps)
        hits += verificationHits(effective, Hit.Source.INSTRUCTION)
        return Verdict(
            hits = hits.distinctBy { it.category },
            needsConfirmation = hits.isNotEmpty(),
            reason = describe(hits),
        )
    }

    /**
     * Review the live screen right before a tap/typing lands.
     *
     * [readable] false means the dump failed, which banking and payment apps
     * commonly force — the case where a silent pass would be most expensive, so
     * it still asks. Verification, again, needs evidence rather than a label.
     */
    fun reviewScreen(
        screenTexts: List<String>,
        readable: Boolean,
        evidence: VerifyEvidence = VerifyEvidence(),
    ): Verdict {
        if (!readable) {
            return Verdict(
                hits = emptyList(),
                needsConfirmation = true,
                reason = "读不到当前界面文字（部分支付/银行类应用会屏蔽），无法判断这一步是否安全",
            )
        }
        val haystack = screenTexts.joinToString("\n")
        val hits = matchText(haystack, Hit.Source.SCREEN).toMutableList()
        hits += verificationHits(evidence, Hit.Source.SCREEN)
        val distinct = hits.distinctBy { it.category }
        return Verdict(
            hits = distinct,
            needsConfirmation = distinct.isNotEmpty(),
            reason = if (distinct.isEmpty()) "" else "当前界面出现：" + describe(distinct),
        )
    }

    /**
     * Verification hits that require real evidence.
     *
     * The two cases the product decides on: an SMS with digits arrived, or a
     * code-shaped secret is actually being typed. Anything else — a page merely
     * mentioning 验证码 — produces no hit, which is what stops ordinary logins
     * from being interrupted.
     */
    fun verificationHits(evidence: VerifyEvidence, source: Hit.Source): List<Hit> {
        val out = ArrayList<Hit>(2)
        if (evidence.smsFound) {
            val where = evidence.smsDetail.ifBlank { "短信" }
            out.add(Hit(Category.VERIFICATION, "刚收到含数字的短信（$where）", source))
        }
        if (evidence.secretTyped) {
            out.add(Hit(Category.VERIFICATION, "正在填入 4–8 位数字验证码", source))
        }
        return out
    }

    private fun matchText(text: String, source: Hit.Source): List<Hit> {
        if (text.isBlank()) return emptyList()
        val lower = text.lowercase()
        val out = ArrayList<Hit>()
        for (cat in Category.entries) {
            // Contextual categories never trigger on wording alone.
            if (cat.contextual) continue
            val kw = cat.keywords.firstOrNull { lower.contains(it.lowercase()) } ?: continue
            out.add(Hit(cat, kw, source))
        }
        // Money and secrets first — that is the order a person wants to read.
        return out.sortedBy { it.category.severity.ordinal * -1 }.distinctBy { it.category }
    }

    private fun describe(hits: List<Hit>): String {
        if (hits.isEmpty()) return ""
        return hits.joinToString("、") { "${it.category.label}（命中「${it.keyword}」）" }
    }

    /** One-line summary of what a run will touch, for the confirmation dialog. */
    fun summarise(hits: List<Hit>): String =
        hits.map { it.category.label }.distinct().joinToString("、")
}
