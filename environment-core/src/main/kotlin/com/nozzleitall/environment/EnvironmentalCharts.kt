package com.nozzleitall.environment

import org.json.JSONObject

/** Bounded reception history, never an estimate of the sensor's measurement time. */
data class ChartSample(val id: String, val binding: String, val time: Long, val session: String,
    val dropped: Long, val values: Map<Channel, Double>, val sequence: Long = 0)

class ChartHistoryException : IllegalStateException("Local history cannot be plotted: unsupported or damaged data. Existing data has not been changed.")

object EnvironmentalCharts {
    const val MAX_SAMPLES = 500
    fun latest(samples: List<ChartSample>, binding: String): ChartSample? = samples.asSequence()
        .filter { it.binding == binding }.maxWithOrNull(compareBy<ChartSample> { it.time }.thenBy { it.sequence })
    fun sample(o: Observation) = ChartSample(o.eventId, o.bindingId, o.receivedAt, o.sessionId,
        o.droppedBefore, o.readings.filter { it.value.isFinite() }.associate { it.channel to it.value }, o.sequence)

    /** Called on the IO dispatcher alongside the bounded history query. */
    fun history(rows: List<String>): List<ChartSample> = try { rows.take(MAX_SAMPLES).map { raw ->
        require(raw.length <= 16_384)
        val j = JSONObject(raw)
        require(j.getInt("encoding") == 1)
        val readings = j.getJSONArray("readings")
        require(readings.length() in 1..16)
        val values = buildMap {
            for (i in 0 until readings.length()) {
                val r = readings.getJSONObject(i)
                val channel = Channel.entries.firstOrNull { it.wire == r.getString("channel") } ?: continue
                val value = r.getDouble("raw") + r.getDouble("offset")
                if (value.isFinite()) put(channel, value)
            }
        }
        ChartSample(j.getString("eventId"), j.getString("binding"), j.getLong("receivedAt"),
            j.getString("session"), j.getLong("droppedBefore"), values, j.optLong("sequence", 0))
    } } catch (_: Exception) { throw ChartHistoryException() }

    fun merge(history: List<ChartSample>, live: List<ChartSample>): List<ChartSample> =
        (history + live).associateBy { it.id }.values.sortedWith(compareBy<ChartSample> { it.time }.thenBy { it.id }).takeLast(MAX_SAMPLES)

    data class Point(val x: Float, val y: Float, val connect: Boolean)
    data class Series(val points: List<Point>, val low: Double, val high: Double, val start: Long, val end: Long)
    fun series(samples: List<ChartSample>, binding: String, channel: Channel): Series? {
        val rows = samples.filter { it.binding == binding && it.values[channel]?.isFinite() == true }.sortedBy { it.time }
        if (rows.isEmpty()) return null
        val minimum = rows.minOf { it.values.getValue(channel) }
        val maximum = rows.maxOf { it.values.getValue(channel) }
        val pad = maxOf((maximum - minimum) * .1, .5)
        val low = minimum - pad; val high = maximum + pad
        val start = rows.first().time; val end = rows.last().time
        val span = maxOf(1.0, end.toDouble() - start.toDouble())
        val points = rows.mapIndexed { i, r ->
            val previous = rows.getOrNull(i - 1)
            Point(if (start == end) .5f else ((r.time.toDouble() - start.toDouble()) / span).toFloat(),
                (1.0 - (r.values.getValue(channel) - low) / (high - low)).toFloat(),
                previous != null && r.session == previous.session && r.sequence > previous.sequence && r.dropped == previous.dropped &&
                    r.time > previous.time && r.time.toDouble() - previous.time.toDouble() <= 120_000.0)
        }
        return Series(points, low, high, start, end)
    }
}
