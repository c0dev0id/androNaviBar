package de.codevoid.andronavibar.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.AttributeSet
import de.codevoid.andronavibar.R

/**
 * Square variant of FocusableButton for use in content panes.
 *
 * Inherits the shared focus ring and two-tap touch model.
 * Clears MaterialButton defaults (insets, minimum sizes, elevation)
 * so layout params are respected for square tiles.
 */
class SquareButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialButtonStyle
) : FocusableButton(context, attrs, defStyleAttr) {

    init {
        insetBottom = 0
        insetTop = 0
        minimumHeight = 0
        minimumWidth = 0
        minHeight = 0
        minWidth = 0
        isAllCaps = false
        iconTint = null
        elevation = 0f
        stateListAnimator = null
        backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        cornerRadius = dpToPx(CORNER_RADIUS_DP)
        rippleColor = ColorStateList.valueOf(
            context.getColor(R.color.colorPrimary) and 0x33FFFFFF
        )
    }
}
