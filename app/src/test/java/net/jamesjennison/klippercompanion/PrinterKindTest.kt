package net.jamesjennison.klippercompanion

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test

/** In-memory SharedPreferences fake with a working edit()/apply(), so saveProfiles' output can be
 * read back through profiles() in the same test - unlike the read-only fake in
 * PrinterPreferencesSecretFailureTest.kt, which only needs to simulate stored reads. */
private class InMemoryPrefs(private val strings: MutableMap<String, String> = mutableMapOf()) : SharedPreferences {
    override fun getAll(): MutableMap<String, *> = strings
    override fun getString(key: String?, defValue: String?): String? = strings[key] ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?) = defValues
    override fun getInt(key: String?, defValue: Int) = defValue
    override fun getLong(key: String?, defValue: Long) = defValue
    override fun getFloat(key: String?, defValue: Float) = defValue
    override fun getBoolean(key: String?, defValue: Boolean) = defValue
    override fun contains(key: String?) = strings.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, String?>()
        private val removals = mutableSetOf<String>()
        override fun putString(key: String?, value: String?) = apply { if (key != null) pending[key] = value }
        override fun putStringSet(key: String?, values: MutableSet<String>?) = apply {}
        override fun putInt(key: String?, value: Int) = apply {}
        override fun putLong(key: String?, value: Long) = apply {}
        override fun putFloat(key: String?, value: Float) = apply {}
        override fun putBoolean(key: String?, value: Boolean) = apply {}
        override fun remove(key: String?) = apply { if (key != null) removals += key }
        override fun clear() = apply { strings.clear() }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() { removals.forEach { strings.remove(it) }; pending.forEach { (k, v) -> if (v == null) strings.remove(k) else strings[k] = v } }
    }
}

class PrinterKindTest {
    @Test fun kindRoundTripsThroughSaveAndLoad() {
        val prefs = InMemoryPrefs()
        val secrets = InMemoryPrefs()
        val profile = PrinterProfile("http://u1.local/", "U1", kind = PrinterKind.SNAPMAKER_U1_PAXX)
        PrinterPreferences.saveProfiles(prefs, secrets, profile.address, listOf(profile))
        val loaded = PrinterPreferences.profiles(prefs, secrets)
        assertEquals(PrinterKind.SNAPMAKER_U1_PAXX, loaded.single().kind)
    }
    private val voronMachine = CustomMachine(300.0, 300.0, 280.0, startGcode = "PRINT_START", endGcode = "PRINT_END")
    @Test fun aSavedPrinterKeepsItsCustomMachineAcrossSaveAndLoad() {
        val prefs = InMemoryPrefs(); val secrets = InMemoryPrefs()
        val custom = PrinterProfile("http://voron.local/", "Voron", kind = PrinterKind.GENERIC_KLIPPER, slicingModel = SlicingPrinterModel.GENERIC_KLIPPER, customMachine = voronMachine)
        val plain = PrinterProfile("http://other.local/", "Other")
        PrinterPreferences.saveProfiles(prefs, secrets, custom.address, listOf(custom, plain))
        val loaded = PrinterPreferences.profiles(prefs, secrets).associateBy { it.name }
        assertEquals(voronMachine, loaded.getValue("Voron").customMachine); assertNull(loaded.getValue("Other").customMachine)
    }

    @Test fun stockU1KindRoundTripsAndPaxxStaysDistinct() {
        val prefs = InMemoryPrefs()
        val secrets = InMemoryPrefs()
        val stock = PrinterProfile("http://stock.local/", "Stock", kind = PrinterKind.SNAPMAKER_U1)
        val paxx = PrinterProfile("http://paxx.local/", "Paxx", kind = PrinterKind.SNAPMAKER_U1_PAXX)
        PrinterPreferences.saveProfiles(prefs, secrets, stock.address, listOf(stock, paxx))
        val loaded = PrinterPreferences.profiles(prefs, secrets).associate { it.address to it.kind }
        assertEquals(PrinterKind.SNAPMAKER_U1, loaded["http://stock.local/"])
        assertEquals("an existing saved PAXX printer must stay PAXX", PrinterKind.SNAPMAKER_U1_PAXX, loaded["http://paxx.local/"])
    }
    @Test fun bambuProfileRoundTripsItsBareHostSerialAndAccessCode() {
        val prefs = InMemoryPrefs()
        val secrets = InMemoryPrefs()
        val profile = PrinterProfile("192.168.1.50", "P1S", kind = PrinterKind.BAMBU_LAB, serial = "01P00A000000000", apiKey = "12345678")
        PrinterPreferences.saveProfiles(prefs, secrets, profile.address, listOf(profile))
        val loaded = PrinterPreferences.profiles(prefs, secrets).single()
        assertEquals(profile, loaded)
    }
    @Test fun serviceFactoryRoutesByKind() {
        val bambu = PrinterProfile("192.168.1.50", kind = PrinterKind.BAMBU_LAB, serial = "01P00A000000000", apiKey = "12345678")
        assertTrue(printerServiceFor(bambu, bambu.address) is BambuPrinterService)
        assertEquals(bambu.address, printerServiceFor(bambu, bambu.address).address)
        assertTrue(printerServiceFor(PrinterProfile("http://u1.local/"), "http://u1.local/") is Moonraker)
        assertTrue(printerServiceFor(null, "http://u1.local/") is Moonraker)
    }
    @Test fun slicingModelAndDeclaredFirmwareVersionRoundTrip() {
        val prefs = InMemoryPrefs()
        val secrets = InMemoryPrefs()
        val profile = PrinterProfile("http://cc1.local/", "CC1", slicingModel = SlicingPrinterModel.ELEGOO_CENTAURI_CARBON, declaredFirmwareVersion = "Release - 26.08.0")
        PrinterPreferences.saveProfiles(prefs, secrets, profile.address, listOf(profile))
        val loaded = PrinterPreferences.profiles(prefs, secrets).single()
        assertEquals(profile, loaded)
        assertEquals(CosmosProfileGeneration.CURRENT, loaded.declaredCosmosProfileGeneration)
    }
    @Test fun missingOrUnrecognizedSlicingModelDefaultsToNull() {
        val prefs = InMemoryPrefs(mutableMapOf("profilesV1" to
            """[{"address":"http://a.local/","name":"A","favorite":false,"cameraId":""},
                {"address":"http://b.local/","name":"B","favorite":false,"cameraId":"","slicingModel":"SOME_FUTURE_MODEL"}]"""))
        val secrets = InMemoryPrefs()
        val profiles = PrinterPreferences.profiles(prefs, secrets)
        assertNull(profiles[0].slicingModel)
        assertNull(profiles[1].slicingModel)
    }
    @Test fun declaredCosmosProfileGenerationIsNullForNonCentauriCarbonModels() {
        assertNull(PrinterProfile("http://u1.local/", slicingModel = SlicingPrinterModel.SNAPMAKER_U1, declaredFirmwareVersion = "26.08.0").declaredCosmosProfileGeneration)
        assertNull(PrinterProfile("http://cc1.local/", slicingModel = SlicingPrinterModel.ELEGOO_CENTAURI_CARBON).declaredCosmosProfileGeneration) // blank version
        assertNull(PrinterProfile("http://cc1.local/", slicingModel = SlicingPrinterModel.ELEGOO_CENTAURI_CARBON, declaredFirmwareVersion = "garbage").declaredCosmosProfileGeneration)
    }
    @Test fun missingOrUnrecognizedKindDefaultsToGenericKlipper() {
        val prefs = InMemoryPrefs(mutableMapOf("profilesV1" to
            """[{"address":"http://a.local/","name":"A","favorite":false,"cameraId":""},
                {"address":"http://b.local/","name":"B","favorite":false,"cameraId":"","kind":"SOME_FUTURE_VENDOR"}]"""))
        val secrets = InMemoryPrefs()
        val profiles = PrinterPreferences.profiles(prefs, secrets)
        assertEquals(PrinterKind.GENERIC_KLIPPER, profiles[0].kind)
        assertEquals(PrinterKind.GENERIC_KLIPPER, profiles[1].kind)
    }
}
