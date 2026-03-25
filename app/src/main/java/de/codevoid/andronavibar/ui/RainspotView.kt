package de.codevoid.andronavibar.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import de.codevoid.andronavibar.R

/**
 * Renders a meteoblue rainSPOT 7×7 grid.
 *
 * The 49-character string is row-major, top-left first. Values 0–9 encode
 * precipitation intensity (0 = none, 9 = severe). Center cell (index 24)
 * is the queried location, marked with an orange dot.
 */
class RainspotView(context: Context) : View(context) {

    private var data = ""

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
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cellRect    = RectF()

    fun setData(s: String) {
        data = s
        invalidate()
    }

    // Force square: height always matches measured width.
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(measuredWidth, measuredWidth)
    }

    override fun onDraw(canvas: Canvas) {
        if (data.length < 49) return

        val gap   = 1.5f
        val cellW = (width  - gap * 8) / 7f
        val cellH = (height - gap * 8) / 7f
        val cornerR = minOf(cellW, cellH) * 0.22f

        for (i in 0 until 49) {
            val row   = i / 7
            val col   = i % 7
            val value = (data[i].digitToIntOrNull() ?: 0).coerceIn(0, 9)
            val left  = gap + col * (cellW + gap)
            val top   = gap + row * (cellH + gap)
            cellRect.set(left, top, left + cellW, top + cellH)

            cellPaint.color = if (value == 0)
                Color.argb(25, 255, 255, 255)   // subtle grid outline when dry
            else
                rainColors[value]
            canvas.drawRoundRect(cellRect, cornerR, cornerR, cellPaint)
        }

        // Center location dot (index 24 = row 3, col 3)
        val cx = gap + 3 * (cellW + gap) + cellW / 2f
        val cy = gap + 3 * (cellH + gap) + cellH / 2f
        val dotR = minOf(cellW, cellH) * 0.22f

        centerPaint.style = Paint.Style.FILL
        centerPaint.color = context.getColor(R.color.colorPrimary)
        canvas.drawCircle(cx, cy, dotR, centerPaint)

        centerPaint.style = Paint.Style.STROKE
        centerPaint.strokeWidth = 1.5f
        centerPaint.color = Color.WHITE
        canvas.drawCircle(cx, cy, dotR + 1.5f, centerPaint)
    }
}
