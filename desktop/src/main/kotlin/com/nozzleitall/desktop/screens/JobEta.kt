package com.nozzleitall.desktop.screens

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** How long a running print has left, worked out from how far it is and how long it has taken. */
object JobEta {
    /** Seconds left, or null while it is too early to tell (under 2 %), when the time taken isn't known, or when the job is done. */
    fun remainingSeconds(fraction: Float, elapsedSeconds: Double?): Double? {
        if (elapsedSeconds == null || !elapsedSeconds.isFinite() || elapsedSeconds <= 0 || fraction < 0.02f || fraction >= 1f) return null
        return elapsedSeconds * (1.0 - fraction) / fraction
    }

    /** The clock time the print should end, such as "4:35 PM", with the day named when it is not today. */
    fun finishesAt(nowMillis: Long, remainingSeconds: Double, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val end = Instant.ofEpochMilli(nowMillis + (remainingSeconds * 1000).toLong()).atZone(zone)
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val time = DateTimeFormatter.ofPattern("h:mm a", locale).format(end)
        return when (java.time.temporal.ChronoUnit.DAYS.between(today, end.toLocalDate())) {
            0L -> time
            1L -> "tomorrow $time"
            else -> DateTimeFormatter.ofPattern("EEE", locale).format(end) + " " + time
        }
    }
}
