package de.codevoid.andronavibar.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import com.google.android.material.button.MaterialButton
import de.codevoid.andronavibar.R
import de.codevoid.andronavibar.dpToPx

/**
 * Base button shared by all interactive elements in the launcher.
 *
 * Owns the visual language: depth gradient, active fill, and the left accent
 * bar that doubles as both focus indicator (white) and active indicator (orange).
 *
 * Supports an optional [buttonIcon] drawn as a full-height square on the left
 * edge (right of the accent bar). Setting it automatically adjusts paddingStart
 * so the button text doesn't overlap the icon slot.
 */
open class FocusableButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialButtonStyle
) : MaterialButton(context, attrs, defStyleAttr) {

    var onFocusRequested: (() -> Unit)? = null

    /** True when the d-pad cursor is on this button. Bar turns white. */
    open var isFocusedButton: Boolean = false
        set(value) { field = value; invalidate() }

    /** True when this button's content pane is currently displayed. Bar turns orange + body fill. */
    open var isActiveButton: Boolean = false
        set(value) { field = value; invalidate() }

    /** 0f–1f fill drawn left-to-right using colorPrimary. Used to show download progress. */
    var downloadProgress: Float = 0f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }

    // ── Icon ─────────────────────────────────────────────────────────────────

    private val iconBackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(20, 255, 255, 255)  // 8% white — frames the icon slot
    }

    /** Icon drawn as a full-height square on the left edge. Setting this adjusts paddingStart. */
    var buttonIcon: Drawable? = null
        set(value) {
            field = value
            if (height > 0) updateIconPadding()
            invalidate()
        }

    private fun updateIconPadding() {
        val iconWidth = if (buttonIcon != null && height > 0) height else 0
        setPaddingRelative(barW.toInt() + iconWidth, paddingTop, paddingEnd, paddingBottom)
    }

    // ── Pre-allocated drawing objects ─────────────────────────────────────────

    protected val cornerRad = resources.dpToPx(CORNER_RADIUS_DP).toFloat()
    protected val barW      = resources.dpToPx(BAR_WIDTH_DP).toFloat()
    protected val drawPath  = Path()

    private val fillPaint     = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.button_active_body)
    }
    private val barPaint      = Paint(Paint.ANTI_ALIAS_FLAG)
    private val depthPaint    = Paint(Paint.ANTI_ALIAS_FLAG)
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        cornerRadius = resources.dpToPx(CORNER_RADIUS_DP)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        drawPath.rewind()
        drawPath.addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), cornerRad, cornerRad, Path.Direction.CW)
        depthPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(
                Color.argb(28, 255, 255, 255),  // 11% white highlight at top
                Color.TRANSPARENT,               // clear at 45%
                Color.argb(20, 0, 0, 0)         // 8% black shadow at bottom
            ),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        if (buttonIcon != null) updateIconPadding()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        // 1. Depth gradient — top-lit look over the button's background colour.
        canvas.save()
        canvas.clipPath(drawPath)
        canvas.drawRect(0f, 0f, w, h, depthPaint)
        canvas.restore()

        // 2. Download progress fill — grows left to right using colorPrimary.
        if (downloadProgress > 0f) {
            progressPaint.color = context.getColor(R.color.colorPrimary)
            progressPaint.alpha = 70
            canvas.save()
            canvas.clipPath(drawPath)
            canvas.drawRect(0f, 0f, w * downloadProgress, h, progressPaint)
            canvas.restore()
        }

        // 3. Active body fill — drawn before super so it sits behind text/icon.
        if (isActiveButton) {
            canvas.save()
            canvas.clipPath(drawPath)
            canvas.drawRect(barW, 0f, w, h, fillPaint)
            canvas.restore()
        }

        // 4. Icon slot and icon drawable (if set).
        onDrawContent(canvas)

        // 5. Text, ripple, etc. from MaterialButton.
        super.onDraw(canvas)

        // 6. Bar — white when focused (cursor), orange when active-only.
        if (isFocusedButton || isActiveButton) {
            barPaint.color = context.getColor(
                if (isFocusedButton) R.color.focus_ring else R.color.colorPrimary
            )
            canvas.save()
            canvas.clipPath(drawPath)
            canvas.drawRect(0f, 0f, barW, h, barPaint)
            canvas.restore()
        }
    }

    /** Draws the icon slot backing rectangle and the [buttonIcon] drawable, if set. */
    open fun onDrawContent(canvas: Canvas) {
        buttonIcon?.let { drawable ->
            val vInset = resources.dpToPx(STROKE_WIDTH_DP) * 2
            val iconSize = height - vInset * 2
            val hInset = barW.toInt() + vInset
            canvas.save()
            canvas.clipPath(drawPath)
            canvas.drawRect(barW, 0f, barW + height, height.toFloat(), iconBackPaint)
            drawable.setBounds(hInset, vInset, hInset + iconSize, vInset + iconSize)
            drawable.draw(canvas)
            canvas.restore()
        }
    }

    companion object {
        const val STROKE_WIDTH_DP = 6
        const val CORNER_RADIUS_DP = 16
        const val BAR_WIDTH_DP = 12
    }
}
