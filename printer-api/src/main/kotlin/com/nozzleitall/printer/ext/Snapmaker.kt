package com.nozzleitall.printer.ext

import com.nozzleitall.printer.Capabilities
import com.nozzleitall.printer.PrinterStatus

/**
 * Snapmaker vendor extensions. Namespaced keys in [PrinterStatus.extensions] and [Capabilities.vendorExtensions]; the
 * shared model never interprets them. Screens that know Snapmaker features read them through these helpers.
 */
object Snapmaker {
    /** Full Spectrum: the U1's multi-toolhead colour mixing. */
    const val FULL_SPECTRUM = "snapmaker.full-spectrum"
    /** PAXX multiACE feeders. */
    const val MULTI_ACE = "snapmaker.multi-ace"
}

/** Whether mixing is possible now, the loaded colours it can draw from, and why not when it can't. */
data class FullSpectrumState(val available: Boolean, val palette: List<String> = emptyList(), val unavailableReason: String? = null) {
    fun toExtension(): Map<String, Any> = buildMap {
        put("available", available); put("palette", palette); unavailableReason?.let { put("unavailableReason", it) }
    }
    companion object {
        fun from(status: PrinterStatus): FullSpectrumState? = (status.extensions[Snapmaker.FULL_SPECTRUM] as? Map<*, *>)?.let { m ->
            FullSpectrumState(m["available"] == true, (m["palette"] as? List<*>)?.map { it.toString() } ?: emptyList(), m["unavailableReason"]?.toString())
        }
    }
}

val Capabilities.fullSpectrum: Boolean get() = Snapmaker.FULL_SPECTRUM in vendorExtensions
