package de.codevoid.andronavibar

import android.content.Context
import android.util.AttributeSet

/**
 * Square variant of FocusableButton for use in content panes.
 *
 * Inherits the shared focus ring and two-tap touch model.
 * Used for apps grid cells, music transport controls, and other
 * pane content that needs automotive-sized interactive elements.
 */
class SquareButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialButtonStyle
) : FocusableButton(context, attrs, defStyleAttr)
