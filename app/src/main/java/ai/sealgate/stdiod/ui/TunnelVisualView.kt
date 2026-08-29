package ai.sealgate.stdiod.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.provider.Settings
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.LinearInterpolator
import ai.sealgate.stdiod.R
import ai.sealgate.stdiod.tunnel.TunnelState
import androidx.core.graphics.drawable.DrawableCompat

/** Visualizes the otherwise-invisible outbound tunnel without inventing telemetry. */
class TunnelVisualView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gatewayDrawable: Drawable = requireNotNull(
        context.getDrawable(R.drawable.sealgate_logo),
    ).mutate()
    private val disconnectedDrawable: Drawable = requireNotNull(
        context.getDrawable(R.drawable.ic_plug_disconnected),
    ).mutate()
    private val cyan = context.getColor(R.color.core_cyan)
    private val green = context.getColor(R.color.circuit_green)
    private val amber = context.getColor(R.color.signal_amber)
    private val red = context.getColor(R.color.infra_red)
    private val grid = context.getColor(R.color.grid_dark)
    private val grey = context.getColor(R.color.graphene_grey)
    private val black = context.getColor(R.color.baseline_black)
    private val density = resources.displayMetrics.density
    private var state: TunnelState? = null
    private var phase = 0f
    private var animator: ValueAnimator? = null

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun setState(newState: TunnelState?) {
        if (state == newState) return
        state = newState
        updateAnimation()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateAnimation()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        drawGrid(canvas, w, h)
        val centerY = h * 0.43f
        drawDirection(canvas, w, centerY)
        drawPhone(canvas, centerY)
        drawGateway(canvas, w, centerY)
    }

    private fun drawGrid(canvas: Canvas, w: Float, h: Float) {
        paint.color = grid
        paint.strokeWidth = 1f
        paint.style = Paint.Style.STROKE
        val step = 24f * density
        var x = 0f
        while (x < w) {
            canvas.drawLine(x, 0f, x, h, paint)
            x += step
        }
        var y = 0f
        while (y < h) {
            canvas.drawLine(0f, y, w, y, paint)
            y += step
        }
    }

    private fun drawDirection(canvas: Canvas, w: Float, centerY: Float) {
        val color = when (state) {
            TunnelState.Connected -> green
            TunnelState.Connecting -> amber
            TunnelState.Disconnected, null -> red
        }
        val startX = 48f * density
        val endX = w - 64f * density
        paint.color = color
        paint.strokeWidth = 2f * density
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.alpha = if (state == null) 130 else 245
        val dashLength = 10f * density
        paint.pathEffect = DashPathEffect(
            floatArrayOf(0.1f * density, dashLength),
            -phase * dashLength,
        )
        canvas.drawLine(startX, centerY, endX, centerY, paint)
        paint.pathEffect = null
        if (state == null || state == TunnelState.Disconnected) {
            drawDisconnectedPlug(canvas, w / 2f, centerY, color)
        } else {
            drawSecureLock(canvas, w / 2f, centerY, color)
        }
    }

    private fun drawDisconnectedPlug(canvas: Canvas, centerX: Float, centerY: Float, color: Int) {
        val plateSize = 40f * density
        paint.style = Paint.Style.FILL
        paint.color = black
        paint.alpha = 245
        canvas.drawRoundRect(
            centerX - plateSize / 2f,
            centerY - plateSize / 2f,
            centerX + plateSize / 2f,
            centerY + plateSize / 2f,
            6f * density,
            6f * density,
            paint,
        )
        val iconSize = 34f * density
        disconnectedDrawable.setBounds(
            (centerX - iconSize / 2f).toInt(),
            (centerY - iconSize / 2f).toInt(),
            (centerX + iconSize / 2f).toInt(),
            (centerY + iconSize / 2f).toInt(),
        )
        DrawableCompat.setTint(disconnectedDrawable, color)
        disconnectedDrawable.alpha = 255
        disconnectedDrawable.draw(canvas)
        paint.alpha = 255
    }

    private fun drawSecureLock(canvas: Canvas, centerX: Float, centerY: Float, color: Int) {
        val plateWidth = 34f * density
        val plateHeight = 40f * density
        paint.style = Paint.Style.FILL
        paint.color = black
        paint.alpha = 245
        canvas.drawRoundRect(
            centerX - plateWidth / 2f,
            centerY - plateHeight / 2f,
            centerX + plateWidth / 2f,
            centerY + plateHeight / 2f,
            6f * density,
            6f * density,
            paint,
        )

        paint.style = Paint.Style.STROKE
        paint.color = color
        paint.alpha = if (state == null) 155 else 255
        paint.strokeWidth = 2f * density
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawArc(
            centerX - 7f * density,
            centerY - 11f * density,
            centerX + 7f * density,
            centerY + 5f * density,
            180f,
            180f,
            false,
            paint,
        )
        canvas.drawRoundRect(
            centerX - 9f * density,
            centerY - 2f * density,
            centerX + 9f * density,
            centerY + 11f * density,
            2f * density,
            2f * density,
            paint,
        )
        paint.style = Paint.Style.FILL
        canvas.drawCircle(centerX, centerY + 4f * density, 1.5f * density, paint)
        paint.alpha = 255
    }

    private fun drawGateway(canvas: Canvas, w: Float, centerY: Float) {
        val centerX = w - 31f * density
        val size = 50f * density
        gatewayDrawable.setBounds(
            (centerX - size / 2f).toInt(),
            (centerY - size / 2f).toInt(),
            (centerX + size / 2f).toInt(),
            (centerY + size / 2f).toInt(),
        )
        // Keep the supplied brand mark at its authored color in every tunnel state.
        gatewayDrawable.alpha = 255
        gatewayDrawable.draw(canvas)

        paint.color = grey
        paint.textSize = sp(9f)
        paint.typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.BOLD)
        paint.letterSpacing = 0.14f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("SEALGATE", centerX, centerY + 43f * density, paint)
        paint.textSize = sp(7f)
        paint.letterSpacing = 0.1f
        canvas.drawText("GATEWAY", centerX, centerY + 54f * density, paint)
        paint.letterSpacing = 0f
    }

    private fun drawPhone(canvas: Canvas, centerY: Float) {
        val centerX = 28f * density
        paint.color = cyan
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        canvas.drawRoundRect(
            centerX - 9f * density,
            centerY - 14f * density,
            centerX + 9f * density,
            centerY + 14f * density,
            2f * density,
            2f * density,
            paint,
        )
        canvas.drawLine(
            centerX - 2f * density,
            centerY + 10f * density,
            centerX + 2f * density,
            centerY + 10f * density,
            paint,
        )
        paint.color = grey
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.BOLD)
        paint.textSize = sp(9f)
        paint.letterSpacing = 0.14f
        canvas.drawText("THIS", centerX, centerY + 43f * density, paint)
        canvas.drawText("PHONE", centerX, centerY + 54f * density, paint)
        paint.letterSpacing = 0f
    }

    private fun updateAnimation() {
        animator?.cancel()
        animator = null
        if (
            !isAttachedToWindow ||
            state == null ||
            state == TunnelState.Disconnected ||
            !animationsEnabled()
        ) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (state == TunnelState.Connected) 2400L else 1800L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun animationsEnabled(): Boolean =
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) != 0f

    private fun sp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        value,
        resources.displayMetrics,
    )
}
