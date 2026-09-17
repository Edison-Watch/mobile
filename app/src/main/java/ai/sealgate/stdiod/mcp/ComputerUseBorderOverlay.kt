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
 * Lifecycle: each observe/control call [signal]s the overlay, which attaches the
 * window (if needed) and [pokes][LiquidMetalBorderView.poke] the frame so it animates.
 * The frame quiesces on its own shortly after the calls stop — the view owns that
 * timing — and nothing is drawn (no GPU work) while idle. The window itself stays
 * attached longer ([KEEP_AWAKE_MILLIS]) purely to hold the screen awake between
 * commands: [WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON] keeps the display from
 * dimming or sleeping for as long as this window is attached, with no WAKE_LOCK
 * permission and no device-wide timeout change. The window is removed once no command
 * has arrived for the full keep-awake window, releasing the screen back to the system
 * timeout. FLAG_KEEP_SCREEN_ON only prevents an already-on screen from sleeping; it
 * cannot wake a screen that is already off, which is consistent with
 * [AndroidComputerSource] refusing to act while the screen is off.
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

    private val removeRunnable = Runnable { removeView() }

    /**
     * Mark computer-use activity of [mode]. Safe to call from any thread; the work
     * is marshalled onto the main looper where the window and its animator live.
     */
    fun signal(mode: Mode) {
        main.post {
            ensureAttached()
            view?.poke(mode)
            main.removeCallbacks(removeRunnable)
            main.postDelayed(removeRunnable, KEEP_AWAKE_MILLIS)
        }
    }

    /** Tear down the overlay for good. Call when the service unbinds or is destroyed. */
    fun destroy() {
        main.post {
            main.removeCallbacks(removeRunnable)
            removeView()
        }
    }

    private fun ensureAttached() {
        if (view != null) return
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
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT,
        )
        if (runCatching { manager.addView(overlay, params) }.isSuccess) {
            view = overlay
        }
    }

    private fun removeView() {
        val overlay = view ?: return
        overlay.stopAnimating()
        runCatching { windowManager?.removeView(overlay) }
        view = null
    }

    private companion object {
        /**
         * How long the (blank, non-drawing) window stays attached after the last
         * command, holding the screen awake between actions via FLAG_KEEP_SCREEN_ON.
         * This is an idle timeout, not a session length: computer use has no explicit
         * end signal (it is a stream of discrete observe/control calls), so the window
         * is released once no command has arrived for this long. A gap LONGER than this
         * between two consecutive actions will let the screen dim/sleep mid-session; 60s
         * comfortably covers normal per-action latency (model + network + UI settle).
         */
        const val KEEP_AWAKE_MILLIS = 60_000L
    }
}
