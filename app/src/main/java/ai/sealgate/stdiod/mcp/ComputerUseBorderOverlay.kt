package ai.sealgate.stdiod.mcp

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.WindowManager

/**
 * Screen-edge "liquid metal" indicator drawn while computer use is observing or
 * controlling the device. The frame lives in a [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY]
 * window, which an [AccessibilityService] may add over any other app WITHOUT the
 * SYSTEM_ALERT_WINDOW ("draw over other apps") permission. The window is not
 * touchable and not focusable, so the gestures the control tools dispatch pass
 * straight through to the app underneath.
 *
 * Lifecycle is transient: each observe/control call [signal]s the overlay, which
 * shows (or refreshes) the animated frame and schedules a debounced hide once the
 * agent goes quiet. Nothing is drawn — and no GPU work happens — while idle.
 *
 * The border DOES appear in accessibility screenshots (it is composited onto the
 * display like any window). It is kept thin so it only touches the extreme screen
 * edge; if agent vision near the edge ever matters, hide the view around
 * [AndroidComputerSource]'s screenshot capture.
 */
class ComputerUseBorderOverlay(private val service: AccessibilityService) {

    /** Which computer-use activity is happening; selects the frame palette. */
    enum class Mode { OBSERVE, CONTROL }

    private val main = Handler(Looper.getMainLooper())
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private var view: LiquidMetalBorderView? = null
    private var attached = false

    private val hideRunnable = Runnable { removeView() }

    /**
     * Mark computer-use activity of [mode]. Safe to call from any thread; the work
     * is marshalled onto the main looper where the window and its animator live.
     */
    fun signal(mode: Mode) {
        main.post {
            ensureAttached()
            view?.setMode(mode)
            view?.startAnimating()
            main.removeCallbacks(hideRunnable)
            main.postDelayed(hideRunnable, LINGER_MILLIS)
        }
    }

    /** Tear down the overlay for good. Call when the service unbinds or is destroyed. */
    fun destroy() {
        main.post {
            main.removeCallbacks(hideRunnable)
            removeView()
        }
    }

    private fun ensureAttached() {
        if (attached) return
        val manager = windowManager ?: return
        val overlay = LiquidMetalBorderView(service)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        )
        val added = runCatching { manager.addView(overlay, params) }.isSuccess
        if (added) {
            view = overlay
            attached = true
        }
    }

    private fun removeView() {
        val overlay = view ?: return
        overlay.stopAnimating()
        runCatching { windowManager?.removeView(overlay) }
        view = null
        attached = false
    }

    private companion object {
        /** How long the frame lingers after the last observe/control call. */
        const val LINGER_MILLIS = 1_500L
    }
}
