package org.soulstone.overwatch.service

import android.os.SystemClock
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OverlayTouchHandlerInstrumentedTest {

    @Test
    fun tapInvokesClickWithoutStartingDrag() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val view = FrameLayout(context)
        var clicks = 0
        var dragStarts = 0
        var moves = 0

        view.setOnClickListener { clicks++ }
        view.setOnTouchListener(
            OverlayTouchHandler(
                tapSlopPx = 12f,
                onGestureStarted = {},
                onDragStart = { dragStarts++ },
                onMove = { _, _, _ -> moves++ },
                isOverDismiss = { false },
                onDropOnDismiss = { error("A tap must not dismiss the overlay") },
                onGestureFinished = {}
            )
        )

        dispatch(view, MotionEvent.ACTION_DOWN, 10f, 10f)
        dispatch(view, MotionEvent.ACTION_UP, 15f, 15f)

        assertEquals(1, clicks)
        assertEquals(0, dragStarts)
        assertEquals(0, moves)
    }

    @Test
    fun dragMovesWithoutInvokingClick() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val view = FrameLayout(context)
        var clicks = 0
        var dragStarts = 0
        var moves = 0
        var finished = 0

        view.setOnClickListener { clicks++ }
        view.setOnTouchListener(
            OverlayTouchHandler(
                tapSlopPx = 12f,
                onGestureStarted = {},
                onDragStart = { dragStarts++ },
                onMove = { _, _, _ -> moves++ },
                isOverDismiss = { false },
                onDropOnDismiss = { error("This drag is not over the dismiss target") },
                onGestureFinished = { finished++ }
            )
        )

        dispatch(view, MotionEvent.ACTION_DOWN, 10f, 10f)
        dispatch(view, MotionEvent.ACTION_MOVE, 30f, 10f)
        dispatch(view, MotionEvent.ACTION_UP, 40f, 10f)

        assertEquals(0, clicks)
        assertEquals(1, dragStarts)
        assertTrue(moves > 0)
        assertEquals(1, finished)
        assertFalse(view.isPressed)
    }

    @Test
    fun cancelFinishesAndResetsGestureBeforeNextTap() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val view = FrameLayout(context)
        var clicks = 0
        var dragStarts = 0
        var finished = 0

        view.setOnClickListener { clicks++ }
        view.setOnTouchListener(
            OverlayTouchHandler(
                tapSlopPx = 12f,
                onGestureStarted = {},
                onDragStart = { dragStarts++ },
                onMove = { _, _, _ -> },
                isOverDismiss = { false },
                onDropOnDismiss = { error("A cancelled gesture must not dismiss") },
                onGestureFinished = { finished++ }
            )
        )

        dispatch(view, MotionEvent.ACTION_DOWN, 10f, 10f)
        dispatch(view, MotionEvent.ACTION_MOVE, 30f, 10f)
        dispatch(view, MotionEvent.ACTION_CANCEL, 30f, 10f)
        dispatch(view, MotionEvent.ACTION_DOWN, 10f, 10f)
        dispatch(view, MotionEvent.ACTION_UP, 10f, 10f)

        assertEquals(1, clicks)
        assertEquals(1, dragStarts)
        assertEquals(2, finished)
    }

    @Test
    fun dragReleasedOverDismissCompletesDismissWithoutClick() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val view = FrameLayout(context)
        var clicks = 0
        var dismissals = 0
        var finished = 0

        view.setOnClickListener { clicks++ }
        view.setOnTouchListener(
            OverlayTouchHandler(
                tapSlopPx = 12f,
                onGestureStarted = {},
                onDragStart = {},
                onMove = { _, _, _ -> },
                isOverDismiss = { true },
                onDropOnDismiss = { dismissals++ },
                onGestureFinished = { finished++ }
            )
        )

        dispatch(view, MotionEvent.ACTION_DOWN, 10f, 10f)
        dispatch(view, MotionEvent.ACTION_MOVE, 30f, 10f)
        dispatch(view, MotionEvent.ACTION_UP, 30f, 10f)

        assertEquals(0, clicks)
        assertEquals(1, dismissals)
        assertEquals(1, finished)
    }

    private fun dispatch(view: FrameLayout, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(
            SystemClock.uptimeMillis(),
            SystemClock.uptimeMillis(),
            action,
            x,
            y,
            0
        )
        try {
            view.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }
}
