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

internal const val DOT_USER_BLUE = 0xFF2196F3.toInt()
internal const val DOT_FLOCK_RED = 0xFFD7263D.toInt()
