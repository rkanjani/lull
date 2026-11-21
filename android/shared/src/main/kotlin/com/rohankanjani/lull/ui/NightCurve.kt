package com.rohankanjani.lull.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rohankanjani.lull.Phase

/** The night drawn as one line: above the midline is warming, below it is cooling. */
@Composable
fun NightCurve(
    phases: List<Phase>,
    modifier: Modifier = Modifier,
    /** 0..1 through the schedule while it is running. */
    progress: Double? = null,
    selection: String? = null,
    lineWidth: Dp = 3.dp,
) {
    Box(modifier.clearAndSetSemantics { }) {
        // The glow is the same line drawn again, blurred, underneath.
        Canvas(Modifier.fillMaxSize().blur(8.dp, BlurredEdgeTreatment.Unbounded).alpha(0.6f)) {
            drawCurve(phases, progress, selection, lineWidth.toPx(), glow = true)
        }
        Canvas(Modifier.fillMaxSize()) {
            drawCurve(phases, progress, selection, lineWidth.toPx(), glow = false)
        }
    }
}

private fun DrawScope.drawCurve(phases: List<Phase>, progress: Double?, selection: String?, lineWidth: Float, glow: Boolean) {
    // Inset so the line's round caps and the progress dot are never clipped at the edges.
    val inset = 8.dp.toPx()
    translate(left = inset) {
        val width = size.width - inset * 2
        val height = size.height
        val total = maxOf(phases.sumOf { it.minutes }, 1).toDouble()
        val mid = height / 2
        val amplitude = height * 0.42f
        fun point(fraction: Double) = Offset(
            (width * fraction).toFloat(),
            (mid - level(fraction * total, phases) * amplitude).toFloat(),
        )

        if (!glow) {
            var elapsed = 0.0
            for (phase in phases) {
                val start = (width * elapsed / total).toFloat()
                val bandWidth = (width * phase.minutes / total).toFloat()
                if (phase.id == selection) {
                    drawRoundRect(Color.White.copy(alpha = 0.07f), Offset(start, 0f), Size(bandWidth, height), CornerRadius(10.dp.toPx()))
                }
                if (elapsed > 0) {
                    drawRect(Color.White.copy(alpha = 0.18f), Offset(start, mid - 5.dp.toPx()), Size(1.dp.toPx(), 10.dp.toPx()))
                }
                elapsed += phase.minutes
            }
            drawLine(
                Color.White.copy(alpha = 0.12f), Offset(0f, mid), Offset(width, mid), strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 5.dp.toPx())),
            )
        }

        val samples = maxOf((width / 2.dp.toPx()).toInt(), 2)
        val line = Path().apply {
            val first = point(0.0)
            moveTo(first.x, first.y)
            for (index in 1..samples) {
                val next = point(index.toDouble() / samples)
                lineTo(next.x, next.y)
            }
        }
        val area = Path().apply {
            addPath(line)
            lineTo(width, mid)
            lineTo(0f, mid)
            close()
        }

        val top = mid - amplitude
        val bottom = mid + amplitude
        val fill = Brush.verticalGradient(
            0f to Theme.hot.copy(alpha = 0.45f),
            0.5f to Theme.warm.copy(alpha = 0f),
            0.5f to Theme.cool.copy(alpha = 0f),
            1f to Theme.deepCool.copy(alpha = 0.45f),
            startY = top, endY = bottom,
        )
        val stroke = Brush.verticalGradient(
            0f to Theme.hot,
            0.3f to Theme.warm,
            0.5f to Color(0.85f, 0.85f, 0.85f),
            0.7f to Theme.cool,
            1f to Theme.deepCool,
            startY = top, endY = bottom,
        )
        val style = Stroke(width = lineWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)

        fun draw(alpha: Float) {
            if (!glow) drawPath(area, fill, alpha = alpha)
            drawPath(line, stroke, style = style, alpha = alpha)
        }

        if (progress == null) {
            draw(1f)
            return@translate
        }
        // The part of the night still ahead is drawn dimmer than what has already happened.
        draw(0.3f)
        clipRect(0f, 0f, (width * progress).toFloat(), height) { draw(1f) }

        val now = point(progress)
        if (glow) {
            drawCircle(Color.White.copy(alpha = 0.7f), 9.dp.toPx(), now)
        } else {
            drawCircle(Color.White, 6.dp.toPx(), now)
        }
    }
}

/** Phase levels joined by short eased ramps, so the line reads as a curve rather than steps. */
fun level(minute: Double, phases: List<Phase>): Double {
    var start = 0.0
    phases.forEachIndexed { index, phase ->
        val end = start + phase.minutes
        if (minute <= end || index == phases.size - 1) {
            if (index > 0) {
                val ramp = ramp(phases[index - 1], phase)
                if (ramp != null && minute - start < ramp) {
                    return blend(phases[index - 1].level, phase.level, 0.5 + (minute - start) / (2 * ramp))
                }
            }
            if (index < phases.size - 1) {
                val ramp = ramp(phase, phases[index + 1])
                if (ramp != null && end - minute < ramp) {
                    return blend(phase.level, phases[index + 1].level, 0.5 - (end - minute) / (2 * ramp))
                }
            }
            return phase.level
        }
        start = end
    }
    return 0.0
}

private fun ramp(a: Phase, b: Phase): Double? {
    val width = minOf(40.0, a.minutes / 2.0, b.minutes / 2.0)
    return if (width > 0) width else null
}

private fun blend(from: Double, to: Double, t: Double): Double {
    val eased = t * t * (3 - 2 * t)
    return from + (to - from) * eased
}
