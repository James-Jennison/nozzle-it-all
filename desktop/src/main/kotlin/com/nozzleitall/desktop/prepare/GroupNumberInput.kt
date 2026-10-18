package com.nozzleitall.desktop.prepare

import java.util.Locale

internal fun groupNumberDisplay(value: Float): String = String.format(Locale.ROOT, "%.1f", value).removeSuffix(".0")

/** Display rounding is not an edit. Only different, valid user input may change geometry. */
internal fun groupNumberCommit(text: String, value: Float, positive: Boolean = false): Float? {
    val input = text.trim()
    if (input == groupNumberDisplay(value)) return null
    return input.replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() && (!positive || it > 0f) && it != value }
}
