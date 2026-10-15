package com.griipbuddy.message.ui.art

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import com.griipbuddy.message.ui.theme.Griip
import kotlin.math.cos
import kotlin.math.sin

/**
 * Original hero illustration for onboarding, drawn in code: a glossy 3-D speech bubble carrying a
 * teal shield with a check, circled by an orbit ring and sparkles. It translates the reference's
 * "glossy object + shield + orbit" composition without using any of its assets.
 */
@Composable
fun HeroBubbleArt(modifier: Modifier = Modifier, animate: Boolean = true) {
    val c = Griip.colors
    val t = rememberInfiniteTransition(label = "hero")
    val orbit by t.animateFloat(0f, 360f, infiniteRepeatable(tween(14000, easing = LinearEasing)), label = "orbit")
    val float by t.animateFloat(-1f, 1f, infiniteRepeatable(tween(2600), RepeatMode.Reverse), label = "float")
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2
        val cy = h / 2
        val bob = if (animate) float * h * 0.012f else 0f

        // ground glow
        drawOval(
            Brush.radialGradient(listOf(c.primary.copy(alpha = 0.28f), Color.Transparent), center = Offset(cx, cy + h * 0.36f), radius = w * 0.4f),
            topLeft = Offset(cx - w * 0.36f, cy + h * 0.30f), size = Size(w * 0.72f, h * 0.12f),
        )

        // back half of orbit ring
        drawOrbit(cx, cy + bob, w, h, front = false, color = c.primaryBright)

        translate(top = bob) {
            // bubble body
            val bw = w * 0.56f
            val bh = h * 0.46f
            val left = cx - bw / 2
            val top = cy - bh * 0.62f
            val r = bw * 0.26f
            val bubble = Path().apply {
                addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(left, top, left + bw, top + bh), CornerRadius(r, r)))
                // tail
                moveTo(left + bw * 0.22f, top + bh - 2f)
                lineTo(left + bw * 0.12f, top + bh + bh * 0.24f)
                lineTo(left + bw * 0.42f, top + bh - 2f)
                close()
            }
            // shadow
            translate(left = w * 0.015f, top = h * 0.025f) { drawPath(bubble, c.primaryDeep.copy(alpha = 0.18f)) }
            drawPath(bubble, Brush.linearGradient(listOf(Color.White, Color(0xFFF2FBF9), Color(0xFFCDEDE8)), start = Offset(left, top), end = Offset(left + bw, top + bh * 1.2f)))
            // gloss
            drawRoundRect(
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.95f), Color.White.copy(alpha = 0f)), startY = top, endY = top + bh * 0.5f),
                topLeft = Offset(left + bw * 0.08f, top + bh * 0.05f), size = Size(bw * 0.6f, bh * 0.32f), cornerRadius = CornerRadius(r * 0.7f),
            )

            // shield
            val sw = bw * 0.42f
            val sh = bh * 0.66f
            val sx = cx - sw / 2
            val sy = top + bh * 0.16f
            val shield = Path().apply {
                moveTo(cx, sy)
                lineTo(sx + sw, sy + sh * 0.16f)
                lineTo(sx + sw, sy + sh * 0.46f)
                cubicTo(sx + sw, sy + sh * 0.78f, cx + sw * 0.18f, sy + sh * 0.94f, cx, sy + sh)
                cubicTo(cx - sw * 0.18f, sy + sh * 0.94f, sx, sy + sh * 0.78f, sx, sy + sh * 0.46f)
                lineTo(sx, sy + sh * 0.16f)
                close()
            }
            drawPath(shield, Brush.linearGradient(listOf(c.primaryBright, c.primary, c.primaryDeep), start = Offset(sx, sy), end = Offset(sx + sw, sy + sh)))
            drawPath(shield, Color.White.copy(alpha = 0.55f), style = Stroke(width = sw * 0.035f))
            // check
            val check = Path().apply {
                moveTo(cx - sw * 0.2f, sy + sh * 0.5f)
                lineTo(cx - sw * 0.04f, sy + sh * 0.65f)
                lineTo(cx + sw * 0.24f, sy + sh * 0.36f)
            }
            drawPath(check, Color.White, style = Stroke(width = sw * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }

        // front half of orbit + satellite dot
        drawOrbit(cx, cy + bob, w, h, front = true, color = c.primaryBright)
        val a = Math.toRadians(orbit.toDouble())
        val ox = cx + cos(a).toFloat() * w * 0.42f
        val oy = cy + bob + sin(a).toFloat() * h * 0.11f
        drawCircle(Brush.radialGradient(listOf(Color.White, c.primaryBright), center = Offset(ox, oy), radius = w * 0.022f), w * 0.022f, Offset(ox, oy))

        // sparkles
        sparkle(Offset(w * 0.18f, h * 0.2f), w * 0.035f, c.primaryBright)
        sparkle(Offset(w * 0.84f, h * 0.28f), w * 0.025f, c.primary)
        sparkle(Offset(w * 0.78f, h * 0.76f), w * 0.02f, c.peach)
        drawCircle(c.primaryBright.copy(alpha = 0.35f), w * 0.012f, Offset(w * 0.25f, h * 0.7f))
        drawCircle(c.lavender.copy(alpha = 0.35f), w * 0.009f, Offset(w * 0.9f, h * 0.5f))
    }
}

private fun DrawScope.drawOrbit(cx: Float, cy: Float, w: Float, h: Float, front: Boolean, color: Color) {
    val rect = Rect(cx - w * 0.42f, cy - h * 0.11f, cx + w * 0.42f, cy + h * 0.11f)
    rotate(-8f, Offset(cx, cy)) {
        drawArc(
            Brush.horizontalGradient(listOf(color.copy(alpha = 0.15f), color.copy(alpha = 0.7f), color.copy(alpha = 0.15f)), startX = rect.left, endX = rect.right),
            startAngle = if (front) 0f else 180f, sweepAngle = 180f, useCenter = false,
            topLeft = rect.topLeft, size = rect.size, style = Stroke(width = w * 0.012f, cap = StrokeCap.Round),
        )
    }
}

private fun DrawScope.sparkle(center: Offset, r: Float, color: Color) {
    val p = Path().apply {
        moveTo(center.x, center.y - r)
        quadraticTo(center.x, center.y, center.x + r, center.y)
        quadraticTo(center.x, center.y, center.x, center.y + r)
        quadraticTo(center.x, center.y, center.x - r, center.y)
        quadraticTo(center.x, center.y, center.x, center.y - r)
        close()
    }
    drawPath(p, color.copy(alpha = 0.85f))
}
