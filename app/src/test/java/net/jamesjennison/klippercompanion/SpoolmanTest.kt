package net.jamesjennison.klippercompanion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse

class SpoolmanTest {
    @Test fun parsesNestedFilamentAndVendorFields() {
        val array = JSONArray("""[{"id":1,"remaining_weight":812.5,"archived":false,
            "filament":{"name":"PLA Basic","material":"PLA","color_hex":"FF7043","weight":1000,
            "vendor":{"name":"Bambu Lab"}}}]""")
        val spools = Spoolman.parseSpools(array)
        assertEquals(1, spools.size)
        val spool = spools.first()
        assertEquals(812.5, spool.remainingWeight!!, 0.0)
        assertEquals(1000.0, spool.totalWeight!!, 0.0)
        assertEquals("PLA Basic", spool.filamentName); assertEquals("Bambu Lab", spool.vendorName)
        assertEquals("Bambu Lab PLA Basic", Spoolman.displayName(spool))
    }
    @Test fun missingFieldsFallBackHonestly() {
        val array = JSONArray("""[{"id":7}]""")
        val spool = Spoolman.parseSpools(array).first()
        assertNull(spool.filamentName); assertNull(spool.vendorName); assertNull(spool.remainingWeight)
        assertEquals("Spool #7", Spoolman.displayName(spool))
    }
    @Test fun nonArrayOrOversizedResponseIsHandledSafely() {
        assertTrue(Spoolman.parseSpools(null).isEmpty())
        assertTrue(Spoolman.parseSpools(JSONObject()).isEmpty())
        val huge = JSONArray(); repeat(2001) { huge.put(JSONObject().put("id", it)) }
        assertThrows(IllegalArgumentException::class.java) { Spoolman.parseSpools(huge) }
    }
    @Test fun inventoryUnavailableWhenComponentMissing() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            server.start()
            val api = Moonraker(server.url("/").toString())
            try {
                val inventory = api.spoolmanInventory()
                assertFalse(inventory.available); assertTrue(inventory.spools.isEmpty()); assertNull(inventory.activeSpoolId)
            } finally { api.close() }
        }
    }
    @Test fun inventoryQueriesSpoolIdThenProxiesTheSpoolmanListCall() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"result":{"spool_id":3}}"""))
            server.enqueue(MockResponse().setBody("""{"result":{"response":[{"id":3,"remaining_weight":500,"filament":{"name":"PETG"}}],"error":null}}"""))
            server.start()
            val api = Moonraker(server.url("/").toString())
            try {
                val inventory = api.spoolmanInventory()
                assertTrue(inventory.available); assertEquals(3, inventory.activeSpoolId); assertEquals(1, inventory.spools.size)
                assertEquals("/server/spoolman/spool_id", server.takeRequest().requestUrl!!.encodedPath)
                val proxy = server.takeRequest()
                assertEquals("/server/spoolman/proxy", proxy.requestUrl!!.encodedPath)
                assertEquals("POST", proxy.method)
                val body = JSONObject(proxy.body.readUtf8())
                assertEquals("GET", body.getString("request_method")); assertEquals("/v1/spool", body.getString("path"))
            } finally { api.close() }
        }
    }
}
