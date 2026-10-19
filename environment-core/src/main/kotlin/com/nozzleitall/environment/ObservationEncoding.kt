package com.nozzleitall.environment

/** Stable ordered v1 encoding. Avoid a JSON DOM/Writer allocation graph on every radio report. */
internal object ObservationEncoding {
    private fun StringBuilder.quoted(value: String) {
        append('"')
        for (c in value) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000c' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') { append("\\u00"); append("0123456789abcdef"[c.code shr 4]); append("0123456789abcdef"[c.code and 15]) } else append(c)
        }
        append('"')
    }
    fun encode(o: Observation): String = buildString(768) {
        append("{\"encoding\":1,\"eventId\":"); quoted(o.eventId)
        append(",\"incarnation\":"); quoted(o.incarnation)
        append(",\"collector\":"); quoted(o.collectorId)
        append(",\"session\":"); quoted(o.sessionId)
        append(",\"clockId\":"); quoted(o.clockId)
        append(",\"bootId\":"); quoted(o.bootId)
        append(",\"sequence\":").append(o.sequence)
        append(",\"binding\":"); quoted(o.bindingId)
        append(",\"bindingRevision\":").append(o.bindingRevision)
        append(",\"observedAt\":").append(o.observedAt)
        append(",\"monotonicNanos\":").append(o.monotonicNanos)
        append(",\"measuredAt\":").append(o.measuredAt)
        append(",\"sourceCounter\":").append(o.sourceCounter)
        append(",\"decoder\":"); quoted(o.decoder)
        append(",\"droppedBefore\":").append(o.droppedBefore)
        append(",\"readings\":[")
        o.readings.forEachIndexed { i, r ->
            if (i != 0) append(',')
            append("{\"channel\":"); quoted(r.channel.wire)
            append(",\"raw\":").append(r.raw).append(",\"offset\":").append(r.offset)
            append(",\"calibrationVersion\":").append(r.calibrationVersion).append('}')
        }
        append("]}")
    }
}
