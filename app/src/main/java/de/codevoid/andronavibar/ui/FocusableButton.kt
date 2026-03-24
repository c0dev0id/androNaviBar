package de.codevoid.andronavibar.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import com.google.android.material.button.MaterialButton
import de.codevoid.andronavibar.R
import de.codevoid.andronavibar.dpToPx

/**
 * Base button with a shared focus ring.
 *
 * All interactive buttons in the launcher inherit from this class.
 * The focus ring is the sole visual focus indicator; it is drawn as a
 * foreground overlay when [isFocusedButton] is true.
 */
open class FocusableButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialButtonStyle
) : MaterialButton(context, attrs, defStyleAttr) {

    var onFocusRequested: (() -> Unit)? = null

    open var isFocusedButton: Boolean = false
        set(value) {
            field = value
            foreground = if (value) focusRing else null
        }

    private val focusRing: GradientDrawable by lazy {
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = resources.dpToPx(CORNER_RADIUS_DP).toFloat()
            setStroke(resources.dpToPx(STROKE_WIDTH_DP), context.getColor(R.color.focus_ring))
            setColor(Color.TRANSPARENT)
        }
    }

    companion object {
        /** Focus ring stroke width in dp — thick for automotive visibility. */
        const val STROKE_WIDTH_DP = 6
        /** Focus ring corner radius in dp. */
        const val CORNER_RADIUS_DP = 16
    }
}
