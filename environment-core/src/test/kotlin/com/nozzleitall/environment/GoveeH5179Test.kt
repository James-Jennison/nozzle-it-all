package com.nozzleitall.environment

import org.junit.Assert.*
import org.junit.Test

class GoveeH5179Test {
    private fun advertisement(name: String?, bytes: ByteArray = byteArrayOf(1, 1, 4, 59, 200.toByte(), 100)) =
        Advertisement("AA:BB:CC:DD:70:41", name, mapOf(1 to bytes), emptyMap(), 1000L, 1000L)

    @Test fun observedAliasDecodesAndMatchesImportedName() {
        // Payload shape/values observed in the owner's BlueZ cache; identity sanitized.
        val decoded = requireNotNull(BleDecoders.decode(advertisement("GV5179_7041")))
        assertEquals("H5179", decoded.model)
        assertEquals("govee-ble", decoded.protocol)
        val values = decoded.readings.associate { it.channel to it.raw }
        assertEquals(27.7, values[Channel.TEMPERATURE]!!, 0.001)
        assertEquals(44.0, values[Channel.HUMIDITY]!!, 0.001)
        assertEquals(100.0, values[Channel.BATTERY_PERCENT]!!, 0.001)
        val candidate = Candidate("AA:BB:CC:DD:70:41", decoded.protocol, decoded.model)
        assertTrue(GoveeDeviceName("11:22:AA:BB:CC:DD:70:41", "H5179", "Centauri Carbon").matches(candidate))
        assertFalse(GoveeDeviceName("11:22:AA:BB:CC:DD:70:42", "H5179", "Different sensor").matches(candidate))
    }

    @Test fun aliasRecognitionIsBoundedAndCanonicalNamesRemainSupported() {
        assertEquals("H5179", BleDecoders.goveeModel("GV5179_abCD"))
        assertEquals("H5179", BleDecoders.goveeModel("Govee_H5179"))
        for (name in listOf(null, "GV5179", "GV5179_704", "GV5179_70411", "GV5179_ZZZZ", "other_GV5179_7041"))
            assertNull(name, BleDecoders.goveeModel(name))
    }

    @Test fun malformedAliasPayloadsRemainRejected() {
        assertNull(BleDecoders.decode(advertisement("GV5179_7041", byteArrayOf(1, 1, 4))))
        assertNull(BleDecoders.decode(advertisement("GV5179_7041", byteArrayOf(1, 1, 4, 59, 200.toByte(), 101))))
        assertNull(BleDecoders.decode(advertisement("GV5179_7041", byteArrayOf(1, 1, 4, 59, 200.toByte(), 128.toByte()))))
    }
}
