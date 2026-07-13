package org.soulstone.overwatch.ui

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable

/** Builds a small filled-circle Marker icon. Used for both the user-position
 *  dot (blue) and the ALPR pins (red) — osmdroid's default teardrop marker
 *  reads as a "click me" affordance which is wrong for a non-interactive
 *  visualization, so we use simple dots instead. Shared by the in-app and
 *  overlay versions of the threat circle. */
internal fun dotDrawable(
    resources: Resources,
    sizePx: Int,
    coreColor: Int
): BitmapDrawable {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f - 1f, outline)
    val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = coreColor }
    canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f - 4f, core)
    return BitmapDrawable(resources, bitmap)
}

/** Builds a ⌖-style position mark (register / gun-sight): a stroked circle with
 *  a cross through it, painted over a dark halo so it reads on any map tile. Used
 *  for the user's own location so it stays distinct from the colored source dots. */
internal fun crosshairDrawable(
    resources: Resources,
    sizePx: Int,
    markColor: Int
): BitmapDrawable {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val c = sizePx / 2f
    val r = sizePx * 0.30f
    val pad = sizePx * 0.07f
    val stroke = sizePx * 0.10f

    val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xB3000000.toInt()
        strokeWidth = stroke + 3f
        strokeCap = Paint.Cap.ROUND
    }
    val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = markColor
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
    }
    // Halo pass then color pass so the mark stays legible on light or dark tiles.
    for (p in listOf(halo, mark)) {
        canvas.drawCircle(c, c, r, p)
        canvas.drawLine(pad, c, sizePx - pad, c, p)   // horizontal
        canvas.drawLine(c, pad, c, sizePx - pad, p)   // vertical
    }
    return BitmapDrawable(resources, bitmap)
}

// Per-source marker colors so geodata reads at a glance.
internal const val DOT_FLOCK_RED = 0xFFD7263D.toInt()       // Flock / DeFlock ALPR cameras
internal const val DOT_WAZE_BLUE = 0xFF2196F3.toInt()       // Waze police reports
internal const val DOT_CITIZEN_PURPLE = 0xFF9C27B0.toInt()  // Citizen incidents
internal const val MARK_USER_WHITE = 0xFFFFFFFF.toInt()     // the user's own position (⌖)
