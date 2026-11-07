package dev.dsh.remote.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * OKLCH 颜色:明度 L(0..1)、彩度 C、色相 H(度)。
 *
 * 整套配色都建立在这里。OKLab 的明度是感知均匀的 —— 同一个 L 在任何色相上
 * 看起来一样亮 —— 所以"只换色相、不动明度"就能让六套配色落在同一档对比度上,
 * 文字的可读性不用一套一套去调。
 *
 * 矩阵来自 Björn Ottosson (2020) 的公开推导。
 */
data class Oklch(val l: Double, val c: Double, val h: Double) {

    /**
     * 转回 sRGB。超出色域时**只降彩度**(明度和色相不动),所以对比度不受影响。
     */
    fun toColor(alpha: Float = 1f): Color {
        val lightness = l.coerceIn(0.0, 1.0)
        var chroma = c.coerceAtLeast(0.0)
        var rgb = linear(lightness, chroma, h)
        if (!inGamut(rgb)) {
            var lo = 0.0
            var hi = chroma
            repeat(24) {
                val mid = (lo + hi) / 2
                if (inGamut(linear(lightness, mid, h))) lo = mid else hi = mid
            }
            chroma = lo
            rgb = linear(lightness, chroma, h)
        }
        fun channel(v: Double): Int = (toGamma(v.coerceIn(0.0, 1.0)) * 255).roundToInt().coerceIn(0, 255)
        return Color(
            red = channel(rgb.first),
            green = channel(rgb.second),
            blue = channel(rgb.third),
            alpha = (alpha.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255),
        )
    }

    companion object {
        fun fromColor(color: Color): Oklch {
            val r = toLinear(color.red.toDouble())
            val g = toLinear(color.green.toDouble())
            val b = toLinear(color.blue.toDouble())
            val lp = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
            val mp = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
            val sp = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
            val l = 0.2104542553 * lp + 0.7936177850 * mp - 0.0040720468 * sp
            val a = 1.9779984951 * lp - 2.4285922050 * mp + 0.4505937099 * sp
            val bb = 0.0259040371 * lp + 0.7827717662 * mp - 0.8086757660 * sp
            val c = sqrt(a * a + bb * bb)
            var h = atan2(bb, a) * 180 / Math.PI
            if (h < 0) h += 360.0
            return Oklch(l, c, h)
        }

        private fun linear(l: Double, c: Double, h: Double): Triple<Double, Double, Double> {
            val rad = h * Math.PI / 180
            val a = c * cos(rad)
            val b = c * sin(rad)
            val lp = l + 0.3963377774 * a + 0.2158037573 * b
            val mp = l - 0.1055613458 * a - 0.0638541728 * b
            val sp = l - 0.0894841775 * a - 1.2914855480 * b
            val l3 = lp * lp * lp
            val m3 = mp * mp * mp
            val s3 = sp * sp * sp
            return Triple(
                4.0767416621 * l3 - 3.3077115913 * m3 + 0.2309699292 * s3,
                -1.2684380046 * l3 + 2.6097574011 * m3 - 0.3413193965 * s3,
                -0.0041960863 * l3 - 0.7034186147 * m3 + 1.7076147010 * s3,
            )
        }

        private fun inGamut(rgb: Triple<Double, Double, Double>): Boolean {
            val eps = 1e-4
            fun ok(v: Double) = v >= -eps && v <= 1 + eps
            return ok(rgb.first) && ok(rgb.second) && ok(rgb.third)
        }

        private fun cbrt(v: Double): Double =
            if (v < 0) -(-v).pow(1.0 / 3.0) else v.pow(1.0 / 3.0)

        private fun toLinear(v: Double): Double =
            if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)

        private fun toGamma(v: Double): Double =
            if (v <= 0.0031308) 12.92 * v else 1.055 * v.pow(1.0 / 2.4) - 0.055
    }
}

/**
 * 把 [color] 换到另一个色相上:明度不动,色相加 [hueShift] 度、彩度乘 [chromaScale],
 * 透明度保留。
 *
 * 中性色(页面、文字、玻璃)靠它跟着强调色走 —— 雾蓝那一整条中性色阶原样转到别的
 * 色相上,各级文字的对比度因此一动不动。
 */
fun retint(color: Color, hueShift: Double, chromaScale: Double): Color {
    if (abs(hueShift) < 1e-9 && abs(chromaScale - 1.0) < 1e-9) return color
    val o = Oklch.fromColor(color)
    return o.copy(c = o.c * chromaScale, h = (((o.h + hueShift) % 360) + 360) % 360)
        .toColor(color.alpha)
}

/** WCAG 2.x 相对亮度对比度(测试与运行时断言共用)。 */
fun contrastRatio(a: Color, b: Color): Double {
    val la = a.luminance()
    val lb = b.luminance()
    val hi = if (la > lb) la else lb
    val lo = if (la > lb) lb else la
    return (hi + 0.05) / (lo + 0.05)
}

/** 相对亮度,按 WCAG 定义(线性化后加权)。 */
private fun Color.luminance(): Double {
    fun lin(v: Float): Double {
        val d = v.toDouble()
        return if (d <= 0.03928) d / 12.92 else ((d + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * lin(red) + 0.7152 * lin(green) + 0.0722 * lin(blue)
}
