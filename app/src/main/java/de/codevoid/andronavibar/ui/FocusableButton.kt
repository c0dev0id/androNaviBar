package de.codevoid.andronavibar.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.MotionEvent
import com.google.android.material.button.MaterialButton
import de.codevoid.andronavibar.R
import de.codevoid.andronavibar.dpToPx

/**
 * Base button with shared focus ring and two-tap touch model.
 *
 * All interactive buttons in the launcher inherit from this class,
 * ensuring a consistent automotive-friendly focus ring and input model:
 * first tap on an unfocused button focuses it (no activation, no ripple);
 * second tap activates.
 *
 * Subclasses provide their own click listener for activation behavior.
 */
open class FocusableButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialButtonStyle
) : MaterialButton(context, attrs, defStyleAttr) {

    /** Fired when this unfocused button receives ACTION_DOWN (focus-only tap). */
    var onFocusRequested: (() -> Unit)? = null

    var isFocusedButton: Boolean = false
        set(value) {
            field = value
            foreground = if (value) focusRing else null
        }

    private val focusRing: GradientDrawable by lazy {
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = resources.dpToPx(CORNER_RADIUS_DP).toFloat()
            setStroke(resources.dpToPx(STROKE_WIDTH_DP), context.getColor(R.color.colorPrimary))
            setColor(Color.TRANSPARENT)
        }
    }

    init {
        // Two-tap touch model: first tap on an unfocused button requests focus
        // without activating. The touch event is consumed so the click listener
        // doesn't fire. When already focused, touch falls through to onClick.
        setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN && !isFocusedButton) {
                onFocusRequested?.invoke()
                true
            } else false
        }
    }

    companion object {
        /** Focus ring stroke width in dp — thick for automotive visibility. */
        const val STROKE_WIDTH_DP = 6
        /** Focus ring corner radius in dp. */
        const val CORNER_RADIUS_DP = 16
    }
}
