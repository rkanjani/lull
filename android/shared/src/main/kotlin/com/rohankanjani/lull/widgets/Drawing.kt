package com.rohankanjani.lull.widgets

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorNode
import androidx.compose.ui.graphics.vector.VectorPath
import com.rohankanjani.lull.Phase
import com.rohankanjani.lull.ui.Theme
import com.rohankanjani.lull.ui.level

/**
 * Widgets can't run Compose drawing, so the night curve is drawn into a bitmap the same way the app draws it:
 * above the midline is warming, below it is cooling, and the night still ahead is dimmer while it runs.
 */
fun nightCurveBitmap(phases: List<Phase>, widthPx: Int, heightPx: Int, density: Float, progress: Double? = null, lineWidthDp: Float = 3f): Bitmap {
    val bitmap = Bitmap.createBitmap(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    // Inset so the line's round caps and the progress dot are never clipped at the edges.
    val inset = 8 * density
    val width = widthPx - inset * 2
    val height = heightPx.toFloat()
    val total = maxOf(phases.sumOf { it.minutes }, 1).toDouble()
    val mid = height / 2
    val amplitude = height * 0.42f
    fun x(fraction: Double) = inset + (width * fraction).toFloat()
    fun y(fraction: Double) = (mid - level(fraction * total, phases) * amplitude).toFloat()

    // The midline and a tick where each phase begins.
    val hairline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.White.copy(alpha = 0.12f).toArgb()
        strokeWidth = density
        pathEffect = DashPathEffect(floatArrayOf(2 * density, 5 * density), 0f)
    }
    canvas.drawLine(inset, mid, inset + width, mid, hairline)
    val tick = Paint().apply { color = Color.White.copy(alpha = 0.18f).toArgb() }
    var elapsed = 0.0
    for (phase in phases) {
        if (elapsed > 0) {
            val at = x(elapsed / total)
            canvas.drawRect(at, mid - 5 * density, at + density, mid + 5 * density, tick)
        }
        elapsed += phase.minutes
    }

    val samples = maxOf((width / (2 * density)).toInt(), 2)
    val line = Path().apply {
        moveTo(x(0.0), y(0.0))
        for (index in 1..samples) {
            val fraction = index.toDouble() / samples
            lineTo(x(fraction), y(fraction))
        }
    }
    val area = Path(line).apply {
        lineTo(inset + width, mid)
        lineTo(inset, mid)
        close()
    }
    val top = mid - amplitude
    val bottom = mid + amplitude
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(
                Theme.hot.copy(alpha = 0.45f).toArgb(), Theme.warm.copy(alpha = 0f).toArgb(),
                Theme.cool.copy(alpha = 0f).toArgb(), Theme.deepCool.copy(alpha = 0.45f).toArgb(),
            ),
            floatArrayOf(0f, 0.5f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )
    }
    fun strokePaint(glow: Boolean) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = lineWidthDp * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        shader = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(Theme.hot.toArgb(), Theme.warm.toArgb(), Color(0.85f, 0.85f, 0.85f).toArgb(), Theme.cool.toArgb(), Theme.deepCool.toArgb()),
            floatArrayOf(0f, 0.3f, 0.5f, 0.7f, 1f), Shader.TileMode.CLAMP,
        )
        if (glow) maskFilter = BlurMaskFilter(8 * density, BlurMaskFilter.Blur.NORMAL)
    }
    val glow = strokePaint(glow = true)
    val stroke = strokePaint(glow = false)

    fun draw(alpha: Int) {
        fill.alpha = alpha
        glow.alpha = alpha * 6 / 10
        stroke.alpha = alpha
        canvas.drawPath(area, fill)
        canvas.drawPath(line, glow)
        canvas.drawPath(line, stroke)
    }

    if (progress == null) {
        draw(255)
        return bitmap
    }
    draw(77)
    canvas.save()
    canvas.clipRect(0f, 0f, x(progress), height)
    draw(255)
    canvas.restore()
    val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
    canvas.drawCircle(x(progress), y(progress), 6 * density, dot)
    return bitmap
}

/** One of the app's Material icons as a bitmap, in a colour, for widgets and tiles. */
fun ImageVector.toBitmap(color: Color, sizePx: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.scale(sizePx / viewportWidth, sizePx / viewportHeight)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color.toArgb() }
    fun drawNode(node: VectorNode) {
        when (node) {
            is VectorGroup -> node.forEach(::drawNode)
            is VectorPath -> canvas.drawPath(PathParser().addPathNodes(node.pathData).toPath().asAndroidPath(), paint)
        }
    }
    drawNode(root)
    return bitmap
}
