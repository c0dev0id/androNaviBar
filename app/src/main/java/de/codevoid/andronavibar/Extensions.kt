package de.codevoid.andronavibar

import android.content.res.Resources
import android.view.ViewGroup
import java.io.File

fun Resources.dpToPx(dp: Int): Int =
    (dp * displayMetrics.density + 0.5f).toInt()

fun buttonIconFile(filesDir: File, index: Int) =
    File(filesDir, "btn_${index}_icon.png")

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
