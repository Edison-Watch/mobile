package ai.sealgate.stdiod.mcp

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.SweepGradient
import android.os.Build
import android.view.Choreographer
import android.view.View
import androidx.annotation.RequiresApi

/**
 * A full-screen, touch-transparent view that paints an animated "liquid metal"
 * frame around the screen edge and nothing in the middle. The metal is a
 * per-pixel shader (simplex-noise FBM warp + sine bands + a five-stop metallic
 * palette, driven by a time uniform) confined to a stroked rounded rect, so only
 * the border ring is filled.
 *
 * On Android 13+ (AGSL [RuntimeShader]) this is a near-direct port of the WebGL
 * reference. Below 13 — or on a software canvas — it falls back to a rotating
 * metallic [SweepGradient], which reads as a moving sheen rather than true
 * refractive metal.
 */
@SuppressLint("ViewConstructor")
class LiquidMetalBorderView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val borderWidthPx = BORDER_WIDTH_DP * density
    private val cornerRadiusPx = CORNER_RADIUS_DP * density

    private var mode: ComputerUseBorderOverlay.Mode = ComputerUseBorderOverlay.Mode.OBSERVE
    private var startNanos = 0L
    private var animating = false

    private val strokeRect = RectF()

    private val runtimeShader: RuntimeShader? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { RuntimeShader(AGSL_SOURCE) }.getOrNull()
        } else {
            null
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = borderWidthPx
    }

    private val frameCallback = Choreographer.FrameCallback {
        if (!animating) return@FrameCallback
        invalidate()
        Choreographer.getInstance().postFrameCallback(this.frameCallback)
    }

    init {
        // No touch handling and nothing behind the ring; the window flags already
        // make this pass-through, this just avoids any accidental focus.
        isClickable = false
        isFocusable = false
    }

    fun setMode(mode: ComputerUseBorderOverlay.Mode) {
        this.mode = mode
    }

    fun startAnimating() {
        if (animating) return
        animating = true
        startNanos = System.nanoTime()
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    fun stopAnimating() {
        animating = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    override fun onDetachedFromWindow() {
        stopAnimating()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val inset = borderWidthPx / 2f
        strokeRect.set(inset, inset, width - inset, height - inset)
        val timeSeconds = (System.nanoTime() - startNanos) / 1_000_000_000f
        val palette = paletteFor(mode)

        val shader = runtimeShader
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            shader != null &&
            canvas.isHardwareAccelerated
        ) {
            drawWithRuntimeShader(canvas, shader, timeSeconds, palette)
        } else {
            drawWithSweepFallback(canvas, timeSeconds, palette)
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun drawWithRuntimeShader(
        canvas: Canvas,
        shader: RuntimeShader,
        timeSeconds: Float,
        palette: Palette,
    ) {
        shader.setFloatUniform("iResolution", width.toFloat(), height.toFloat())
        shader.setFloatUniform("iTime", timeSeconds)
        shader.setColorUniform("cA", palette.a)
        shader.setColorUniform("cB", palette.b)
        shader.setColorUniform("cC", palette.c)
        shader.setColorUniform("cD", palette.d)
        shader.setColorUniform("cE", palette.e)
        paint.shader = shader
        canvas.drawRoundRect(strokeRect, cornerRadiusPx, cornerRadiusPx, paint)
    }

    private fun drawWithSweepFallback(canvas: Canvas, timeSeconds: Float, palette: Palette) {
        val cx = width / 2f
        val cy = height / 2f
        val sweep = SweepGradient(
            cx,
            cy,
            intArrayOf(palette.a, palette.c, palette.e, palette.c, palette.a),
            floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f),
        )
        val rotation = android.graphics.Matrix().apply {
            setRotate((timeSeconds * SWEEP_DEGREES_PER_SEC) % 360f, cx, cy)
        }
        sweep.setLocalMatrix(rotation)
        paint.shader = sweep
        canvas.drawRoundRect(strokeRect, cornerRadiusPx, cornerRadiusPx, paint)
    }

    private fun paletteFor(mode: ComputerUseBorderOverlay.Mode): Palette = when (mode) {
        // Observe: cool silver/chrome — reading, not touching.
        ComputerUseBorderOverlay.Mode.OBSERVE -> Palette(
            a = Color.rgb(0x3A, 0x45, 0x52),
            b = Color.rgb(0x8A, 0x9B, 0xA8),
            c = Color.rgb(0xE8, 0xF0, 0xF6),
            d = Color.rgb(0x9F, 0xB4, 0xC4),
            e = Color.rgb(0x5C, 0x74, 0x86),
        )
        // Control: warm gold/amber — actively driving the device.
        ComputerUseBorderOverlay.Mode.CONTROL -> Palette(
            a = Color.rgb(0x5A, 0x3B, 0x0E),
            b = Color.rgb(0xC9, 0x8A, 0x2B),
            c = Color.rgb(0xFF, 0xF1, 0xC2),
            d = Color.rgb(0xE7, 0xA8, 0x3A),
            e = Color.rgb(0x8A, 0x54, 0x12),
        )
    }

    private data class Palette(val a: Int, val b: Int, val c: Int, val d: Int, val e: Int)

    private companion object {
        const val BORDER_WIDTH_DP = 6f
        const val CORNER_RADIUS_DP = 28f
        const val SWEEP_DEGREES_PER_SEC = 60f

        // AGSL (Android 13+). SKSL dialect: float2/half4, entry half4 main(float2).
        val AGSL_SOURCE = """
            uniform float2 iResolution;
            uniform float  iTime;
            layout(color) uniform half4 cA;
            layout(color) uniform half4 cB;
            layout(color) uniform half4 cC;
            layout(color) uniform half4 cD;
            layout(color) uniform half4 cE;

            float3 permute(float3 x) { return mod((x * 34.0 + 1.0) * x, 289.0); }

            // Ashima 2D simplex noise.
            float snoise(float2 v) {
                const float4 C = float4(0.211324865405187, 0.366025403784439,
                                        -0.577350269189626, 0.024390243902439);
                float2 i  = floor(v + dot(v, C.yy));
                float2 x0 = v - i + dot(i, C.xx);
                float2 i1 = (x0.x > x0.y) ? float2(1.0, 0.0) : float2(0.0, 1.0);
                float4 x12 = x0.xyxy + C.xxzz;
                x12.xy -= i1;
                i = mod(i, 289.0);
                float3 p = permute(permute(i.y + float3(0.0, i1.y, 1.0))
                                   + i.x + float3(0.0, i1.x, 1.0));
                float3 m = max(0.5 - float3(dot(x0, x0), dot(x12.xy, x12.xy),
                                            dot(x12.zw, x12.zw)), 0.0);
                m = m * m;
                m = m * m;
                float3 x = 2.0 * fract(p * C.www) - 1.0;
                float3 h = abs(x) - 0.5;
                float3 ox = floor(x + 0.5);
                float3 a0 = x - ox;
                m *= 1.79284291400159 - 0.85373472095314 * (a0 * a0 + h * h);
                float3 g;
                g.x  = a0.x * x0.x + h.x * x0.y;
                g.yz = a0.yz * x12.xz + h.yz * x12.yw;
                return 130.0 * dot(m, g);
            }

            float fbm(float2 p) {
                float sum = 0.0;
                float amp = 0.5;
                for (int i = 0; i < 5; i++) {
                    sum += amp * snoise(p);
                    p *= 2.0;
                    amp *= 0.5;
                }
                return sum;
            }

            half3 palette(float t) {
                t = clamp(t, 0.0, 1.0);
                const float s = 0.16;
                float wa = exp(-((t - 0.00) * (t - 0.00)) / (s * s));
                float wb = exp(-((t - 0.25) * (t - 0.25)) / (s * s));
                float wc = exp(-((t - 0.50) * (t - 0.50)) / (s * s));
                float wd = exp(-((t - 0.75) * (t - 0.75)) / (s * s));
                float we = exp(-((t - 1.00) * (t - 1.00)) / (s * s));
                half3 acc = wa * cA.rgb + wb * cB.rgb + wc * cC.rgb + wd * cD.rgb + we * cE.rgb;
                return acc / (wa + wb + wc + wd + we);
            }

            half4 main(float2 fragCoord) {
                float2 uv = fragCoord / iResolution;
                float2 p = uv * 3.0;
                float2 warp = float2(fbm(p + iTime * 0.15),
                                     fbm(p + 7.3 + iTime * 0.12));
                float n = fbm(p + warp);
                float bands = 0.0;
                bands += sin(uv.x * 10.0 + iTime * 1.3 + n * 3.0);
                bands += sin(uv.y * 8.0  - iTime * 1.1 + n * 2.5);
                bands += sin((uv.x + uv.y) * 6.0 + iTime * 0.9);
                bands += sin(length(uv - 0.5) * 14.0 - iTime * 1.6 + n * 2.0);
                float t = bands * 0.125 + 0.5;
                half3 col = palette(t);
                col += half3(smoothstep(0.86, 1.0, t)) * 0.55;
                return half4(clamp(col, 0.0, 1.0), 1.0);
            }
        """.trimIndent()
    }
}
