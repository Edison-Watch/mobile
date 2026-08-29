package ai.sealgate.stdiod.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import ai.sealgate.stdiod.R
import ai.sealgate.stdiod.tunnel.TunnelState
import androidx.core.graphics.drawable.DrawableCompat

/** Renders a shallow, system-template-friendly tunnel diagram for BigPictureStyle. */
object NotificationTunnelArtwork {

    private const val WIDTH = 1024
    private const val HEIGHT = 256
    const val FRAME_COUNT = 12
    private val frameCache = mutableMapOf<FrameKey, Bitmap>()
    private var cachedState: TunnelState? = null

    @Synchronized
    fun render(context: Context, state: TunnelState, frame: Int = 0): Bitmap {
        val frameIndex = if (state == TunnelState.Disconnected) 0 else frame % FRAME_COUNT
        val key = FrameKey(state, frameIndex)
        if (cachedState != state) {
            frameCache.clear()
            cachedState = state
        }
        frameCache[key]?.let { return it }

        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val route = when (state) {
            TunnelState.Connected -> context.getColor(R.color.circuit_green)
            TunnelState.Connecting -> context.getColor(R.color.signal_amber)
            TunnelState.Disconnected -> context.getColor(R.color.infra_red)
        }

        canvas.drawColor(context.getColor(R.color.baseline_black))
        drawGrid(canvas, paint, context.getColor(R.color.grid_dark))
        drawRoute(canvas, paint, route, frameIndex)
        if (state == TunnelState.Disconnected) {
            drawDisconnectedPlug(context, canvas, paint, route)
        } else {
            drawSecureLock(canvas, paint, route, context.getColor(R.color.baseline_black))
        }
        drawPhone(canvas, paint, context.getColor(R.color.core_cyan))
        drawGateway(context, canvas)
        drawLabels(canvas, paint, context.getColor(R.color.graphene_grey))
        frameCache[key] = bitmap
        return bitmap
    }

    private fun drawGrid(canvas: Canvas, paint: Paint, color: Int) {
        paint.color = color
        paint.strokeWidth = 2f
        repeat(WIDTH / 64 + 1) { x ->
            canvas.drawLine(x * 64f, 0f, x * 64f, HEIGHT.toFloat(), paint)
        }
        repeat(HEIGHT / 64 + 1) { y ->
            canvas.drawLine(0f, y * 64f, WIDTH.toFloat(), y * 64f, paint)
        }
    }

    private fun drawRoute(
        canvas: Canvas,
        paint: Paint,
        color: Int,
        frame: Int,
    ) {
        val centerY = 104f
        paint.color = color
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 6f
        paint.strokeCap = Paint.Cap.ROUND
        paint.pathEffect = DashPathEffect(floatArrayOf(1f, 24f), -frame * 2f)
        canvas.drawLine(138f, centerY, 864f, centerY, paint)
        paint.pathEffect = null
    }

    private fun drawPhone(canvas: Canvas, paint: Paint, color: Int) {
        paint.color = color
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 7f
        canvas.drawRoundRect(62f, 54f, 116f, 150f, 9f, 9f, paint)
        canvas.drawLine(80f, 136f, 98f, 136f, paint)
    }

    private fun drawSecureLock(canvas: Canvas, paint: Paint, color: Int, background: Int) {
        paint.style = Paint.Style.FILL
        paint.color = background
        canvas.drawRoundRect(486f, 52f, 580f, 156f, 16f, 16f, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 7f
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = color
        canvas.drawArc(510f, 64f, 556f, 116f, 180f, 180f, false, paint)
        canvas.drawRoundRect(502f, 94f, 564f, 139f, 7f, 7f, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(533f, 116f, 5f, paint)
    }

    private fun drawDisconnectedPlug(
        context: Context,
        canvas: Canvas,
        paint: Paint,
        color: Int,
    ) {
        paint.style = Paint.Style.FILL
        paint.color = context.getColor(R.color.baseline_black)
        canvas.drawRoundRect(481f, 51f, 585f, 157f, 16f, 16f, paint)
        val drawable = requireNotNull(context.getDrawable(R.drawable.ic_plug_disconnected)).mutate()
        DrawableCompat.setTint(drawable, color)
        drawable.bounds = Rect(487, 58, 579, 150)
        drawable.draw(canvas)
    }

    private fun drawGateway(context: Context, canvas: Canvas) {
        val drawable = requireNotNull(context.getDrawable(R.drawable.sealgate_logo)).mutate()
        drawable.bounds = Rect(874, 34, 1014, 174)
        drawable.draw(canvas)
    }

    private fun drawLabels(canvas: Canvas, paint: Paint, muted: Int) {
        paint.style = Paint.Style.FILL
        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        paint.textAlign = Paint.Align.CENTER
        paint.letterSpacing = 0.12f

        paint.color = muted
        paint.textSize = 27f
        canvas.drawText("PHONE", 89f, 211f, paint)
        canvas.drawText("SEALGATE", 944f, 211f, paint)
        paint.letterSpacing = 0f
    }

    private data class FrameKey(val state: TunnelState, val frame: Int)
}
