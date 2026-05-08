package org.soulstone.overwatch.service

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlin.math.abs
import org.soulstone.overwatch.MainActivity
import org.soulstone.overwatch.ui.OverlayBubble

/**
 * Owns the floating threat-circle bubble — a [ComposeView] hosted in a
 * [WindowManager] window at TYPE_APPLICATION_OVERLAY.
 *
 * Lifecycle:
 *  - [show] is idempotent. Silently no-ops if SYSTEM_ALERT_WINDOW isn't
 *    granted, so flipping the setting doesn't crash on a denied permission.
 *  - [hide] removes the view and tears down the owner.
 *  - The DetectionService calls [show] / [hide] based on the running ×
 *    overlayEnabled product. Permission revocation between show calls is
 *    handled silently — the bubble just doesn't appear.
 *
 * Touch model:
 *  - The window flag set lets touches outside the bubble pass through to
 *    whatever app is underneath (FLAG_NOT_TOUCH_MODAL) and never steals IME
 *    focus (FLAG_NOT_FOCUSABLE).
 *  - Touches *inside* the bubble are intercepted at the View layer for drag
 *    and tap-to-open. Compose doesn't see them — the bubble is a render-only
 *    visualization.
 */
class OverlayManager(private val context: Context) {

    private companion object {
        private const val TAG = "OverlayManager"
        private const val INITIAL_X = 60
        private const val INITIAL_Y = 240
        private const val TAP_SLOP_PX = 12
    }

    private val wm: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null
    private var params: WindowManager.LayoutParams? = null

    fun show() {
        if (view != null) return
        if (!Settings.canDrawOverlays(context)) {
            Log.i(TAG, "Overlay permission not granted; skipping show()")
            return
        }

        val newOwner = OverlayOwner()
        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(newOwner)
            setViewTreeSavedStateRegistryOwner(newOwner)
            setContent { OverlayBubble() }
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
            x = INITIAL_X
            y = INITIAL_Y
        }

        composeView.setOnTouchListener(DragHandler(lp))

        try {
            wm.addView(composeView, lp)
            view = composeView
            owner = newOwner
            params = lp
            Log.i(TAG, "Overlay bubble attached")
        } catch (e: Exception) {
            // Most common: WindowManager$BadTokenException if perm was revoked
            // between the canDrawOverlays check and addView. Tear down and
            // bail; service can retry on next state flip.
            Log.w(TAG, "Failed to attach overlay: ${e.message}")
            newOwner.destroy()
        }
    }

    fun hide() {
        val v = view ?: return
        try {
            wm.removeView(v)
        } catch (e: Exception) {
            Log.w(TAG, "removeView failed: ${e.message}")
        }
        owner?.destroy()
        view = null
        owner = null
        params = null
        Log.i(TAG, "Overlay bubble detached")
    }

    /** Drag with raw coords, tap if movement stayed under [TAP_SLOP_PX]. */
    private inner class DragHandler(private val lp: WindowManager.LayoutParams) :
        View.OnTouchListener {

        private var startX = 0
        private var startY = 0
        private var touchDownX = 0f
        private var touchDownY = 0f
        private var moved = false

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
                    if (abs(dx) > TAP_SLOP_PX || abs(dy) > TAP_SLOP_PX) {
                        moved = true
                    }
                    if (moved) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        // Tap → bring the host app forward.
                        val intent = Intent(context, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                        try { context.startActivity(intent) } catch (_: Exception) {}
                    }
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Compose's [ComposeView] requires both a [LifecycleOwner] and a
     * [SavedStateRegistryOwner] in its view tree. A bare [android.app.Service]
     * isn't an SSR owner, so we synthesize one bound to the bubble's lifetime.
     * The lifecycle is forced to RESUMED on construction (Compose only renders
     * at STARTED+) and DESTROYED on [destroy].
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
