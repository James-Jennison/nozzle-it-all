package com.nozzleitall.environment

/** Bounded, pure diagnostic decoders. Protocol decode is NOT firmware/calibration/thermal qualification. */
object BleDecoders {
    const val VERSION = "nia-ble-1"
    private val models = arrayOf("H5100", "H5110", "H5074", "H5179")
    private val gv5179Alias = Regex("GV5179_[0-9A-Fa-f]{4}")
    /** Only the captured bare alias is added; preserve existing canonical framing.
     * Extend variants with evidence rather than guessing from a partial suffix. */
    fun goveeModel(name: String?): String? = when {
        name == null -> null
        gv5179Alias.matches(name) -> "H5179"
        else -> models.firstOrNull { name.contains(it) }
    }
    fun decode(a: Advertisement): Decoded? {
        if (a.manufacturer.size > 16 || a.services.size > 16) return null
        a.services.entries.firstOrNull { it.key.lowercase() == "0000fcd2-0000-1000-8000-00805f9b34fb" }?.let { return bthome(it.value) }
        val model = goveeModel(a.name) ?: return null
        for ((company, bytes) in a.manufacturer) {
            if (company == 0x004c || bytes.size > 64) continue
            fun u(i: Int) = bytes[i].toInt() and 255
            fun le(i: Int) = u(i) or (u(i + 1) shl 8)
            val t: Double; val h: Double; val b: Int
            when {
                (model == "H5100" || model == "H5110" || (model == "H5179" && a.name?.contains("GV5179") == true)) && bytes.size == 6 -> {
                    if (u(5) and 128 != 0) return null
                    val packed = (u(2) shl 16) or (u(3) shl 8) or u(4)
                    val magnitude = packed and 0x7fffff
                    t = (magnitude / 1000) / 10.0 * if (packed and 0x800000 != 0) -1 else 1
                    h = (magnitude % 1000) / 10.0; b = u(5) and 127
                }
                model == "H5074" && bytes.size == 7 -> { t = le(1).toShort() / 100.0; h = le(3) / 100.0; b = u(5) }
                model == "H5179" && bytes.size == 9 -> { t = le(4).toShort() / 100.0; h = le(6) / 100.0; b = u(8) }
                else -> continue
            }
            if (t !in -40.0..100.0 || h !in 0.0..100.0 || b !in 0..100) return null
            return Decoded("govee-ble", model, listOf(Reading(Channel.TEMPERATURE, t), Reading(Channel.HUMIDITY, h), Reading(Channel.BATTERY_PERCENT, b.toDouble())))
        }
        return null
    }
    fun bthome(bytes: ByteArray): Decoded? {
        if (bytes.size !in 2..64) return null
        fun u(i: Int) = bytes[i].toInt() and 255
        if (u(0) and 0xe0 != 0x40 || u(0) and 1 != 0) return null // v2, encrypted unsupported; never seek a key.
        var i = 1; var counter: Int? = null
        val readings = linkedMapOf<Channel, Reading>()
        while (i < bytes.size) {
            val type = u(i++)
            val length = when (type) { 0x00, 0x01, 0x2e -> 1; 0x02, 0x03, 0x0c -> 2; else -> return null }
            if (i + length > bytes.size) return null
            val raw = u(i) or (if (length == 2) u(i + 1) shl 8 else 0)
            i += length
            val pair = when (type) {
                0x00 -> { if (counter != null) return null; counter = raw; continue }
                0x01 -> Channel.BATTERY_PERCENT to raw.toDouble()
                0x02 -> { if (raw == 0x8000) return null; Channel.TEMPERATURE to raw.toShort() / 100.0 }
                0x03 -> { if (raw == 0xffff) return null; Channel.HUMIDITY to raw / 100.0 }
                0x2e -> Channel.HUMIDITY to raw.toDouble()
                else -> { if (raw == 0xffff) return null; Channel.BATTERY_VOLTAGE to raw / 1000.0 }
            }
            if (readings.containsKey(pair.first)) return null
            if ((pair.first == Channel.HUMIDITY || pair.first == Channel.BATTERY_PERCENT) && pair.second !in 0.0..100.0) return null
            readings[pair.first] = Reading(pair.first, pair.second)
        }
        return readings.values.takeIf { it.isNotEmpty() }?.let { Decoded("bthome-v2", "BTHome v2", it.toList(), counter) }
    }
}
