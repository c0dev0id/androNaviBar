package de.codevoid.andronavibar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.content.res.Resources
import android.view.ViewGroup
import java.io.File

fun Resources.dpToPx(dp: Int): Int =
    (dp * displayMetrics.density + 0.5f).toInt()

fun buttonIconFile(filesDir: File, index: Int) =
    File(filesDir, "btn_${index}_icon.png")

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** Render [emoji] centred on a 256×256 bitmap with a surface_card background. */
fun Context.renderEmojiDrawable(emoji: String): BitmapDrawable {
    val size = 256
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = getColor(R.color.surface_card) }
    canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), bgPaint)
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize  = size * 0.65f
    }
    val fm = textPaint.fontMetrics
    canvas.drawText(emoji, size / 2f, size / 2f - (fm.ascent + fm.descent) / 2f, textPaint)
    return BitmapDrawable(resources, bmp)
}
