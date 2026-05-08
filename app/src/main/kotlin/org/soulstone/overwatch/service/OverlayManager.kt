package org.soulstone.overwatch.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlin.math.abs
import kotlin.math.sqrt
import org.soulstone.overwatch.MainActivity
import org.soulstone.overwatch.ui.OverlayBubble

/**
 * Owns the floating threat-circle bubble — a [ComposeView] hosted in a
 * [WindowManager] window at TYPE_APPLICATION_OVERLAY.
 *
 * Touch model:
 *  - The bubble window has FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL, so
 *    touches outside the bubble pass through to whatever app is underneath
 *    and the bubble never steals IME focus.
 *  - Touches *inside* the bubble are intercepted by [TouchInterceptor], a
 *    FrameLayout wrapper whose onInterceptTouchEvent always returns true.
 *    Without that wrapper the inner osmdroid MapView consumes ACTION_DOWN
 *    for its own pan handling and the OnTouchListener never sees the gesture.
 *  - Drag updates the window LayoutParams via WindowManager.updateViewLayout.
 *  - Tap (movement under TAP_SLOP_PX) launches MainActivity.
 *
 * Dismiss zone:
 *  - When the user begins dragging, a separate WindowManager view ([DismissView])
 *    appears at bottom-center showing an X. The bubble's screen-space center
 *    is checked against the X's bounds on each MOVE; on UP, if the bubble was
 *    released over the X, [onDismissed] fires (caller flips the setting off
 *    so the toggle and the bubble state stay in sync).
 *  - The dismiss zone uses FLAG_NOT_TOUCHABLE so it never steals the gesture
 *    — it's purely visual feedback.
 */
class OverlayManager(
    private val context: Context,
    private val onDismissed: () -> Unit
) {

    private companion object {
        private const val TAG = "OverlayManager"
        private const val INITIAL_X_DP = 24
        private const val INITIAL_Y_DP = 120
        private const val TAP_SLOP_PX = 12
        private const val BUBBLE_SIZE_DP = 140
        private const val DISMISS_SIZE_DP = 88
        private const val DISMISS_BOTTOM_MARGIN_DP = 100
        /** Extra slop around the dismiss zone — released within this radius
         *  counts as "dropped on X". */
        private const val DISMISS_HIT_SLOP_DP = 16
    }

    private val wm: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = context.resources.displayMetrics.density
    private val bubbleSizePx = (BUBBLE_SIZE_DP * density).toInt()

    private var container: TouchInterceptor? = null
    private var owner: OverlayOwner? = null
    private var params: WindowManager.LayoutParams? = null

    private var dismissView: DismissView? = null
    private var dismissCenterX = 0f
    private var dismissCenterY = 0f
    private var dismissHitRadius = 0f

    fun show() {
        if (container != null) return
        if (!Settings.canDrawOverlays(context)) {
            Log.i(TAG, "Overlay permission not granted; skipping show()")
            return
        }

        val newOwner = OverlayOwner()
        val composeView = ComposeView(context).apply {
            setContent { OverlayBubble() }
        }
        // Wrap so we can intercept *before* MapView's own touch handling.
        // Compose's WindowRecomposer reads findViewTreeLifecycleOwner from
        // the *window-root* view (= the wrapper here, since it's what's
        // attached to WindowManager). Setting the owner only on the inner
        // ComposeView throws IllegalStateException at composition startup —
        // that was the v0.3.1 crash.
        val wrapper = TouchInterceptor(context).apply {
            setViewTreeLifecycleOwner(newOwner)
            setViewTreeSavedStateRegistryOwner(newOwner)
            addView(
                composeView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (INITIAL_X_DP * density).toInt()
            y = (INITIAL_Y_DP * density).toInt()
        }

        wrapper.setOnTouchListener(DragHandler(lp))

        try {
            wm.addView(wrapper, lp)
            container = wrapper
            owner = newOwner
            params = lp
            Log.i(TAG, "Overlay bubble attached")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to attach overlay: ${e.message}")
            newOwner.destroy()
        }
    }

    fun hide() {
        val v = container ?: return
        try { wm.removeView(v) } catch (e: Exception) {
            Log.w(TAG, "removeView failed: ${e.message}")
        }
        owner?.destroy()
        container = null
        owner = null
        params = null
        hideDismissZone()
        Log.i(TAG, "Overlay bubble detached")
    }

    private fun showDismissZone() {
        if (dismissView != null) return
        val sizePx = (DISMISS_SIZE_DP * density).toInt()
        val marginPx = (DISMISS_BOTTOM_MARGIN_DP * density).toInt()

        // Precompute screen-space center so MOVE checks don't traverse the
        // view tree on every frame. Use displayMetrics (sufficient — a real
        // multi-display split would need WindowManager#getCurrentWindowMetrics
        // on API 30+, but the bubble lives on the user's primary display).
        val dm = context.resources.displayMetrics
        dismissCenterX = dm.widthPixels / 2f
        dismissCenterY = dm.heightPixels - marginPx - sizePx / 2f
        dismissHitRadius = sizePx / 2f + DISMISS_HIT_SLOP_DP * density

        val v = DismissView(context)
        val lp = WindowManager.LayoutParams(
            sizePx, sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = marginPx
        }
        try {
            wm.addView(v, lp)
            dismissView = v
        } catch (e: Exception) {
            Log.w(TAG, "Failed to attach dismiss zone: ${e.message}")
        }
    }

    private fun hideDismissZone() {
        val v = dismissView ?: return
        try { wm.removeView(v) } catch (_: Exception) {}
        dismissView = null
    }

    private fun isOverDismiss(bubbleX: Int, bubbleY: Int): Boolean {
        if (dismissView == null) return false
        val cx = bubbleX + bubbleSizePx / 2f
        val cy = bubbleY + bubbleSizePx / 2f
        val dx = cx - dismissCenterX
        val dy = cy - dismissCenterY
        return sqrt((dx * dx + dy * dy).toDouble()) < dismissHitRadius
    }

    /** Drag with raw coords; tap if movement stayed under [TAP_SLOP_PX]. */
    private inner class DragHandler(private val lp: WindowManager.LayoutParams) :
        View.OnTouchListener {

        private var startX = 0
        private var startY = 0
        private var touchDownX = 0f
        private var touchDownY = 0f
        private var moved = false

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, ev: MotionEvent): Boolean {
            return when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    touchDownX = ev.rawX
                    touchDownY = ev.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - touchDownX
                    val dy = ev.rawY - touchDownY
                    if (!moved && (abs(dx) > TAP_SLOP_PX || abs(dy) > TAP_SLOP_PX)) {
                        moved = true
                        showDismissZone()
                    }
                    if (moved) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
                        dismissView?.setHighlighted(isOverDismiss(lp.x, lp.y))
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved && isOverDismiss(lp.x, lp.y)) {
                        // Released on the X — tear down and signal the caller
                        // so the persisted setting flips off too.
                        hide()
                        onDismissed()
                    } else if (!moved) {
                        // Tap → bring the host app forward.
                        val intent = Intent(context, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                        try { context.startActivity(intent) } catch (_: Exception) {}
                    }
                    hideDismissZone()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    hideDismissZone()
                    true
                }
                else -> false
            }
        }
    }

    /** Always-claiming wrapper. Without this, the osmdroid MapView descendant
     *  consumes ACTION_DOWN for pan handling and the OnTouchListener never
     *  fires — drags pan the map instead of moving the bubble. */
    private class TouchInterceptor(context: Context) : FrameLayout(context) {
        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true
    }

    /** Bottom-center dismiss target — translucent dark circle with a white X
     *  that flips red when the bubble is hovering over it. Drawn manually so
     *  we don't need to ship a vector resource for one-off use. */
    private class DismissView(context: Context) : View(context) {
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        private val xStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            strokeWidth = 8f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private var highlighted = false

        fun setHighlighted(value: Boolean) {
            if (highlighted != value) {
                highlighted = value
                invalidate()
            }
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = minOf(cx, cy)
            bg.color = if (highlighted) 0xDDD7263D.toInt() else 0xCC1A1A1A.toInt()
            canvas.drawCircle(cx, cy, r - 4f, bg)
            val inset = r * 0.35f
            canvas.drawLine(cx - inset, cy - inset, cx + inset, cy + inset, xStroke)
            canvas.drawLine(cx + inset, cy - inset, cx - inset, cy + inset, xStroke)
        }
    }

    /**
     * Compose's [ComposeView] requires both a LifecycleOwner and a
     * [SavedStateRegistryOwner] in its view tree. A bare Service isn't an SSR
     * owner, so we synthesize one bound to the bubble's lifetime. The
     * lifecycle is forced to RESUMED on construction (Compose only renders at
     * STARTED+) and DESTROYED on [destroy].
     */
    private class OverlayOwner : SavedStateRegistryOwner {
        private val lifecycleReg = LifecycleRegistry(this)
        private val ssrController = SavedStateRegistryController.create(this)

        init {
            ssrController.performAttach()
            ssrController.performRestore(null)
            lifecycleReg.currentState = Lifecycle.State.RESUMED
        }

        override val lifecycle: Lifecycle get() = lifecycleReg
        override val savedStateRegistry: SavedStateRegistry
            get() = ssrController.savedStateRegistry

        fun destroy() {
            lifecycleReg.currentState = Lifecycle.State.DESTROYED
        }
    }
}
