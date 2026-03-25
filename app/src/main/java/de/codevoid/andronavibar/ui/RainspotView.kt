package de.codevoid.andronavibar.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import de.codevoid.andronavibar.R
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Renders a meteoblue rainSPOT 7×7 grid.
 *
 * The 49-character string is row-major, top-left first. Values 0–9 encode
 * precipitation intensity (0 = none, 9 = severe). Center cell (index 24)
 * is the queried location, marked with an orange dot.
 *
 * [azimuth] rotates the data so that the device's heading points to the top
 * of the grid (heading-up), matching the wind direction arrows. The grid
 * lines stay axis-aligned; only which source cell maps to each output cell
 * changes. Nearest-neighbour sampling over the 7×7 discrete array.
 */
class RainspotView(context: Context) : View(context) {

    private var data = ""

    /** Device heading in degrees (0 = north, 90 = east). Triggers redraw on change. */
    var azimuth: Float = 0f
        set(value) { field = value; invalidate() }

    // 0 = no rain (transparent) → 9 = severe (bright cyan)
    private val rainColors = intArrayOf(
        Color.argb(  0,   0,   0,   0),   // 0 — none
        Color.argb( 50,  40, 100, 200),   // 1 — trace
        Color.argb( 90,  30, 120, 210),   // 2
        Color.argb(130,  20, 140, 220),   // 3
        Color.argb(165,  10, 160, 230),   // 4
        Color.argb(195,   0, 175, 238),   // 5
        Color.argb(215,   0, 190, 245),   // 6
        Color.argb(232,   0, 208, 252),   // 7
        Color.argb(248,   0, 225, 255),   // 8 — heavy
        Color.argb(255,  80, 245, 255),   // 9 — severe
    )

    private val cellPaint   = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotColor    = context.getColor(R.color.colorPrimary)
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = dotColor }
    private val cellRect    = RectF()

    // Cached per-size geometry — recomputed only in onSizeChanged().
    private val gap = 1.5f
    private var cellW   = 0f
    private var cellH   = 0f
    private var cornerR = 0f
    private var dotCx   = 0f
    private var dotCy   = 0f
    private var dotR    = 0f

    fun setData(s: String) {
        data = s
        invalidate()
    }

    // Force square: height always matches measured width.
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(measuredWidth, measuredWidth)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cellW   = (w - gap * 8) / 7f
        cellH   = (h - gap * 8) / 7f
        cornerR = minOf(cellW, cellH) * 0.22f
        dotCx   = gap + 3 * (cellW + gap) + cellW / 2f
        dotCy   = gap + 3 * (cellH + gap) + cellH / 2f
        dotR    = minOf(cellW, cellH) * 0.22f
    }

    override fun onDraw(canvas: Canvas) {
        if (data.length < 49) return

        val rad  = Math.toRadians(azimuth.toDouble())
        val cosA = cos(rad)
        val sinA = sin(rad)

        for (row in 0 until 7) {
            for (col in 0 until 7) {
                // Map output cell (row, col) back to source cell in north-up data.
                // dr/dc are offsets from center (3,3). Rotating by +azimuth clockwise
                // brings the heading direction to the top of the grid.
                val dr = row - 3.0
                val dc = col - 3.0
                val srcRow = (dc * sinA + dr * cosA).roundToInt().coerceIn(0, 6) + 3
                val srcCol = (dc * cosA - dr * sinA).roundToInt().coerceIn(0, 6) + 3
                // coerceIn above is relative; adjust: coerce the final index
                val si = srcRow.coerceIn(0, 6) * 7 + srcCol.coerceIn(0, 6)
                val value = (data[si].digitToIntOrNull() ?: 0).coerceIn(0, 9)

                val left = gap + col * (cellW + gap)
                val top  = gap + row * (cellH + gap)
                cellRect.set(left, top, left + cellW, top + cellH)

                cellPaint.color = if (value == 0)
                    Color.argb(25, 255, 255, 255)
                else
                    rainColors[value]
                canvas.drawRoundRect(cellRect, cornerR, cornerR, cellPaint)
            }
        }

        // Center location dot — always at cell (3,3), unaffected by data rotation.
        centerPaint.style = Paint.Style.FILL
        canvas.drawCircle(dotCx, dotCy, dotR, centerPaint)

        centerPaint.style = Paint.Style.STROKE
        centerPaint.strokeWidth = 1.5f
        centerPaint.color = Color.WHITE
        canvas.drawCircle(dotCx, dotCy, dotR + 1.5f, centerPaint)
        centerPaint.color = dotColor  // restore for next draw
    }
}
