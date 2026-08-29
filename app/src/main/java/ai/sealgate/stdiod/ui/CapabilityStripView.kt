package ai.sealgate.stdiod.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import ai.sealgate.stdiod.R

/** A compact, non-interactive map of the local MCP modules bundled in the app. */
class CapabilityStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val cyan = context.getColor(R.color.core_cyan)
    private val grey = context.getColor(R.color.graphene_grey)
    private val outline = context.getColor(R.color.outline_dark)
    private val labels = listOf("Device", "Battery", "Wi-Fi", "Bluetooth", "USB")
    private val density = resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cellWidth = width / labels.size.toFloat()
        labels.forEachIndexed { index, label ->
            val centerX = cellWidth * (index + 0.5f)
            if (index > 0) {
                paint.color = outline
                paint.strokeWidth = density
                canvas.drawLine(cellWidth * index, 8f * density, cellWidth * index, height - 8f * density, paint)
            }
            drawIcon(canvas, index, centerX, 24f * density)
            paint.color = grey
            paint.style = Paint.Style.FILL
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = android.graphics.Typeface.create(
                "sans-serif",
                android.graphics.Typeface.NORMAL,
            )
            paint.textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                10f,
                resources.displayMetrics,
            )
            canvas.drawText(label, centerX, height - 10f * density, paint)
        }
    }

    private fun drawIcon(canvas: Canvas, index: Int, x: Float, y: Float) {
        paint.color = cyan
        paint.strokeWidth = 1.8f * density
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.SQUARE
        paint.strokeJoin = Paint.Join.MITER
        val s = 10f * density
        when (index) {
            0 -> {
                canvas.drawRoundRect(RectF(x - s * 0.62f, y - s, x + s * 0.62f, y + s), 2f, 2f, paint)
                canvas.drawLine(x - s * 0.18f, y + s * 0.72f, x + s * 0.18f, y + s * 0.72f, paint)
            }
            1 -> {
                canvas.drawRect(x - s * 0.58f, y - s * 0.82f, x + s * 0.58f, y + s, paint)
                canvas.drawLine(x - s * 0.22f, y - s, x + s * 0.22f, y - s, paint)
                paint.style = Paint.Style.FILL
                canvas.drawRect(x - s * 0.38f, y + s * 0.25f, x + s * 0.38f, y + s * 0.76f, paint)
            }
            2 -> {
                path.reset()
                path.moveTo(x - s, y - s * 0.25f)
                path.quadTo(x, y - s * 1.1f, x + s, y - s * 0.25f)
                canvas.drawPath(path, paint)
                path.reset()
                path.moveTo(x - s * 0.62f, y + s * 0.14f)
                path.quadTo(x, y - s * 0.4f, x + s * 0.62f, y + s * 0.14f)
                canvas.drawPath(path, paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(x, y + s * 0.66f, 1.7f * density, paint)
            }
            3 -> {
                path.reset()
                path.moveTo(x, y - s)
                path.lineTo(x + s * 0.62f, y - s * 0.38f)
                path.lineTo(x - s * 0.42f, y + s * 0.5f)
                path.lineTo(x, y + s)
                path.close()
                canvas.drawPath(path, paint)
                canvas.drawLine(x - s * 0.65f, y - s * 0.55f, x + s * 0.62f, y + s * 0.42f, paint)
            }
            else -> {
                canvas.drawLine(x, y - s, x, y + s * 0.58f, paint)
                canvas.drawLine(x, y - s, x - s * 0.34f, y - s * 0.64f, paint)
                canvas.drawLine(x, y - s, x + s * 0.34f, y - s * 0.64f, paint)
                canvas.drawLine(x, y + s * 0.1f, x - s * 0.58f, y - s * 0.28f, paint)
                canvas.drawCircle(x - s * 0.66f, y - s * 0.34f, 1.8f * density, paint)
                canvas.drawLine(x, y + s * 0.58f, x + s * 0.5f, y + s * 0.28f, paint)
                paint.style = Paint.Style.FILL
                canvas.drawRect(x + s * 0.38f, y + s * 0.16f, x + s * 0.62f, y + s * 0.4f, paint)
            }
        }
    }
}
