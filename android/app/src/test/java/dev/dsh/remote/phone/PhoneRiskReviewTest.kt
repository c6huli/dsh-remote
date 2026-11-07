package dev.dsh.remote.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The narrowed rule: a verification code only stops the run when the code is
 * real — an SMS with digits arrived, or a code-shaped secret is being typed.
 *
 * The regression this guards is the old behaviour, where any page printing
 * "验证码" (every ordinary login screen) parked the run for confirmation.
 */
class PhoneRiskReviewTest {

    private fun screen(vararg texts: String) =
        PhoneRiskReview.reviewScreen(texts.toList(), readable = true)

    private fun instruction(text: String, vararg steps: PhoneStep) =
        PhoneRiskReview.reviewInstruction(text, steps.toList())

    // ---- 验证码：只有字样，不拦 ----

    @Test
    fun `login page merely mentioning the word does not prompt`() {
        val v = screen("请输入验证码", "获取验证码", "登录", "注册")
        assertFalse("纯界面字样不应触发确认", v.needsConfirmation)
        assertTrue(v.hits.none { it.category == PhoneRiskReview.Category.VERIFICATION })
    }

    @Test
    fun `instruction asking for a code without a value does not prompt`() {
        val v = instruction("帮我输入验证码")
        assertFalse(v.needsConfirmation)
    }

    // ---- 验证码：密钥真的填入，拦 ----

    @Test
    fun `typing a 6-digit code prompts`() {
        val v = instruction("输入 123456", PhoneStep.TypeText("123456"))
        assertTrue(v.needsConfirmation)
        assertTrue(v.hits.any { it.category == PhoneRiskReview.Category.VERIFICATION })
    }

    @Test
    fun `typing a 4-digit code prompts`() {
        val v = instruction("填一下 8899", PhoneStep.TypeText("8899"))
        assertTrue(v.needsConfirmation)
    }

    @Test
    fun `typing normal text does not prompt`() {
        val v = instruction("输入 你好世界", PhoneStep.TypeText("你好世界"))
        assertFalse(v.needsConfirmation)
    }

    @Test
    fun `a long number that is not code-shaped does not prompt`() {
        // 9 digits is outside the 4–8 OTP window.
        val v = instruction("输入 123456789", PhoneStep.TypeText("123456789"))
        assertFalse(v.needsConfirmation)
    }

    // ---- 验证码：短信真的到了，拦 ----

    @Test
    fun `sms evidence prompts even when the screen is plain`() {
        val v = PhoneRiskReview.reviewScreen(
            listOf("输入框", "确定"),
            readable = true,
            evidence = PhoneRiskReview.VerifyEvidence(smsFound = true, smsDetail = "messaging｜验证码••••"),
        )
        assertTrue(v.needsConfirmation)
        assertTrue(v.hits.any { it.category == PhoneRiskReview.Category.VERIFICATION })
        // The hint must not leak the digits.
        assertFalse(v.reason.contains("123456"))
    }

    @Test
    fun `code shaped check accepts 4 to 8 digits only`() {
        assertTrue(PhoneRiskReview.looksLikeCode("1234"))
        assertTrue(PhoneRiskReview.looksLikeCode(" 12345678 "))
        assertFalse(PhoneRiskReview.looksLikeCode("123"))
        assertFalse(PhoneRiskReview.looksLikeCode("123456789"))
        assertFalse(PhoneRiskReview.looksLikeCode("12a456"))
        assertFalse(PhoneRiskReview.looksLikeCode(""))
    }

    // ---- 其它类别仍按关键词拦 ----

    @Test
    fun `payment screen still prompts`() {
        assertTrue(screen("确认支付", "¥29.90").needsConfirmation)
    }

    @Test
    fun `order screen still prompts`() {
        assertTrue(screen("提交订单", "去结算").needsConfirmation)
    }

    @Test
    fun `identity screen still prompts`() {
        assertTrue(screen("请输入身份证号", "真实姓名").needsConfirmation)
    }

    @Test
    fun `captcha is still screen triggered`() {
        // Human-verification widgets block the flow immediately; no "code later"
        // state exists, so wording is the right trigger.
        assertTrue(screen("拖动滑块完成验证").needsConfirmation)
    }

    @Test
    fun `benign screen does not prompt`() {
        assertFalse(screen("微信", "通讯录", "发现", "我").needsConfirmation)
    }

    @Test
    fun `unreadable screen still fails closed`() {
        val v = PhoneRiskReview.reviewScreen(emptyList(), readable = false)
        assertTrue("读不到界面时要保守拦截", v.needsConfirmation)
        assertTrue(v.reason.isNotBlank())
    }

    // ---- 顺序：钱优先展示 ----

    @Test
    fun `money categories are listed before secrets`() {
        val v = screen("确认支付", "验证码")
        assertEquals(
            PhoneRiskReview.Category.PAYMENT,
            v.hits.first().category,
        )
    }
}
