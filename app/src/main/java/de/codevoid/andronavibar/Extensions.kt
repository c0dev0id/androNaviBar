package de.codevoid.andronavibar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.content.res.Resources
import android.view.ViewGroup
import com.caverock.androidsvg.SVG
import java.io.File

fun Resources.dpToPx(dp: Int): Int =
    (dp * displayMetrics.density + 0.5f).toInt()

fun buttonIconFile(filesDir: File, index: Int) =
    File(filesDir, "btn_${index}_icon.png")

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/**
 * Load a weather SVG from `assets/weather/[assetName]` and rasterise it to a
 * [sizePx]×[sizePx] transparent bitmap. No background is drawn.
 */
fun Context.loadWeatherSvg(assetName: String, sizePx: Int): BitmapDrawable {
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    try {
        val svg = SVG.getFromAsset(assets, "weather/$assetName")
        svg.documentWidth  = sizePx.toFloat()
        svg.documentHeight = sizePx.toFloat()
        svg.renderToCanvas(Canvas(bmp))
    } catch (_: Exception) { /* return transparent bitmap on failure */ }
    return BitmapDrawable(resources, bmp)
}

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
