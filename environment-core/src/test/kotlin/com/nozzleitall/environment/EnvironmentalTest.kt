package com.nozzleitall.environment

import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EnvironmentalTest {
    private fun file() = Files.createTempDirectory("nia-env-test").resolve("environment/control.json").toFile()
    private class Sql(file: File) : SqlStore {
        val c = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
        override fun execute(sql: String, vararg args: Any?) { c.prepareStatement(sql).use { s -> args.forEachIndexed { i, a -> s.setObject(i + 1, a) }; s.execute(); Unit } }
        override fun query(sql: String, vararg args: Any?): List<List<String?>> = c.prepareStatement(sql).use { s -> args.forEachIndexed { i, a -> s.setObject(i + 1, a) }; s.executeQuery().use { r -> buildList { while (r.next()) add((1..r.metaData.columnCount).map { r.getString(it) }) } } }
        override fun <T> transaction(block: () -> T): T { c.autoCommit = false; try { val v = block(); c.commit(); return v } catch (e: Throwable) { c.rollback(); throw e } finally { c.autoCommit = true } }
        override fun close() = c.close()
    }
    private fun history() = EnvironmentalHistory(SqlStoreFactory { Sql(Files.createTempFile("nia-env-history", ".db").toFile()) })
    private fun observation(id: String = "one", incarnation: String = "inc", at: Long = 1000L) = Observation(id, incarnation, "collector", "session", 1, "binding", 1, at, at + 1, 1, decoder = BleDecoders.VERSION, readings = listOf(Reading(Channel.TEMPERATURE, 23.0)))
    @Test fun offAndIdleHaveNoEffects() {
        var opens = 0; var transports = 0
        val path = file()
        val c = EnvironmentalCoordinator(path, EnvironmentalHistory(SqlStoreFactory { opens++; error("unexpected DB") }), { transports++; error("unexpected radio") })
        assertEquals(EnvironmentalState.DISABLED, c.view.value.state); assertFalse(path.exists())
        c.foreground(true); c.enable(); assertEquals(0, opens); assertEquals(0, transports)
        assertEquals(EnvironmentalState.ENABLED_IDLE, EnvironmentalGate(path).state)
        c.close(); assertEquals(0, opens); assertEquals(0, transports)
    }
    @Test fun corruptControlFailsClosedAndIsPreserved() {
        val p = file(); p.parentFile.mkdirs(); p.writeText("bad")
        val g = EnvironmentalGate(p)
        assertEquals(EnvironmentalState.DISABLED_RECOVERY, g.state)
        assertThrows(IllegalStateException::class.java) { g.enable() }
        assertEquals("bad", p.readText())
    }
    @Test fun grantRejectsOtherIssuerRevisionExpirationAndRevocation() {
        var now = 1L
        val g = EnvironmentalGate(file()) { now }; val other = EnvironmentalGate(file()) { now }
        g.enable(); other.enable()
        val binding = Binding(sensorId = "s", address = "test", protocol = "bthome-v2", model = "BTHome v2")
        val grant = g.begin(Purpose.COLLECTION, listOf(binding), 100)
        assertTrue(g.valid(grant, binding)); assertFalse(other.valid(grant)); assertFalse(g.valid(grant, binding.copy(revision = 2)))
        now = 101; assertFalse(g.valid(grant)); now = 2
        g.revoke(); assertFalse(g.valid(grant))
        assertThrows(IllegalStateException::class.java) { g.commit(grant, binding) { error("must not commit") } }
    }
    @Test fun rebootNeverRestoresCollection() {
        val p = file(); val g = EnvironmentalGate(p); g.enable(); g.begin(Purpose.DISCOVERY, emptyList(), 1000000)
        assertEquals(EnvironmentalState.ENABLED_IDLE, EnvironmentalGate(p).state)
    }
    @Test fun retryAndConflictAreDifferent() {
        history().use { h ->
            val o = observation()
            assertEquals("inserted", h.append(o, "inc")); assertEquals("duplicate", h.append(o.copy(receivedAt = 3000), "inc"))
            assertEquals("conflict", h.append(o.copy(observedAt = 5000), "inc")); assertEquals(1, h.page().size)
            assertThrows(IllegalArgumentException::class.java) { h.append(o.copy(incarnation = "old"), "inc") }
        }
    }
    @Test fun pagingHandlesSameTimestampAndRetention() {
        history().use { h ->
            repeat(510) { h.append(observation("id%04d".format(it)), "inc") }
            val page = h.page(); assertEquals(500, page.size)
            val last = JSONObject(page.last()); assertEquals(10, h.page(last.getLong("observedAt"), last.getString("eventId")).size)
            assertThrows(IllegalArgumentException::class.java) { h.page(limit = 501) }
            h.retain(31 * 86400000L); assertTrue(h.page().isEmpty())
        }
    }
    @Test fun assignmentIsHalfOpenAndRejectsBackdatedRewrite() {
        history().use { h ->
            val b = h.bind(Candidate("test", "govee-ble", "H5100"), "sensor")
            val e = Equipment(name = "Room"); h.saveEquipment(e)
            val p = Position(equipmentId = e.id, name = "Shelf"); h.savePosition(p)
            h.assign(b.sensorId, p.id, 100); h.assign(b.sensorId, p.id, 200)
            val placements = h.entities("placement").sortedBy { it.getLong("from") }
            assertEquals(200L, placements.first().getLong("until")); assertTrue(placements.last().isNull("until"))
            assertThrows(IllegalArgumentException::class.java) { h.assign(b.sensorId, p.id, 150) }
            assertFalse(Placement(sensorId = "s", positionId = "p", from = 100, until = 200).contains(200))
        }
    }
    @Test fun calibrationPreservesRawAndVersion() {
        history().use { h ->
            val b = h.bind(Candidate("test", "bthome-v2", "BTHome v2"), "sensor")
            h.calibrate(b, Channel.HUMIDITY, -4.3)
            val first = h.calibrated(b, listOf(Reading(Channel.HUMIDITY, 34.4))).single()
            h.calibrate(b, Channel.HUMIDITY, 1.0)
            assertEquals(34.4, first.raw, .0001); assertEquals(30.1, first.value, .0001); assertEquals(1, first.calibrationVersion)
            assertEquals(2, h.calibrated(b, listOf(first.copy(offset = 0.0))).single().calibrationVersion)
        }
    }
    @Test fun exportAndDeleteAreExplicitAndIncarnationFenced() {
        history().use { h ->
            h.append(observation(), "inc"); val exported = mutableListOf<String>(); h.export { exported += it }
            assertEquals(2, exported.size); assertEquals(1001L, JSONObject(exported.last()).getLong("receivedAt"))
            h.deleteAll(); assertTrue(h.page().isEmpty())
        }
        val g = EnvironmentalGate(file()); val old = g.incarnation; g.deleteIntent(); assertNotEquals(old, g.incarnation); g.finishDelete(); assertEquals(EnvironmentalState.DISABLED, g.state)
    }
    @Test fun bthomeSignedTemperatureHumidityAndPacketCounter() {
        val d = BleDecoders.bthome(byteArrayOf(0x40, 0, 255.toByte(), 2, 0x2e, 0xfb.toByte(), 3, 0xd2.toByte(), 0x04))!!
        assertEquals(255, d.counter); assertEquals(-12.34, d.readings[0].raw, .001); assertEquals(12.34, d.readings[1].raw, .001)
    }
    @Test fun bthomeRejectsEncryptionTruncationSentinelAndDuplicateChannels() {
        for (bytes in listOf(byteArrayOf(0x41, 1, 100), byteArrayOf(0x40, 2, 0), byteArrayOf(0x40, 3, -1, -1), byteArrayOf(0x40, 1, 101), byteArrayOf(0x40, 1, 50, 1, 60))) assertNull(BleDecoders.bthome(bytes))
    }
    @Test fun goveePackedSignAndError() {
        fun decode(b: Int) = BleDecoders.decode(Advertisement("test", "Govee_H5100", mapOf(1 to byteArrayOf(0, 0, 0x81.toByte(), 0xe2.toByte(), 0x08, b.toByte())), emptyMap(), 0, 0))
        val d = decode(100)!!; assertEquals(-12.3, d.readings[0].raw, .001); assertEquals(40.0, d.readings[1].raw, .001)
        assertNull(decode(0x80)); assertNull(decode(127))
    }
    @Test fun arbitraryMalformedInputNeverThrows() {
        val random = java.util.Random(1)
        repeat(10000) { val b = ByteArray(random.nextInt(100)); random.nextBytes(b); BleDecoders.bthome(b); BleDecoders.decode(Advertisement("test", "H5074", mapOf(1 to b), emptyMap(), 0, 0)) }
    }
    @Test fun delayedTransportCannotSurviveForegroundStop() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val closed = CountDownLatch(1)
        val c = EnvironmentalCoordinator(file(), history(), { BleTransport { _, _ -> entered.countDown(); release.await(5, TimeUnit.SECONDS); object : RadioSession { override fun close() { closed.countDown() } } } })
        c.foreground(true); c.enable(); c.discover(); assertTrue(entered.await(5, TimeUnit.SECONDS)); c.foreground(false); release.countDown()
        assertTrue(closed.await(5, TimeUnit.SECONDS)); assertEquals(EnvironmentalState.ENABLED_IDLE, c.view.value.state); c.close()
    }
    @Test fun unknownFutureSchemaIsPreserved() {
        val path = Files.createTempFile("nia-env-future", ".db").toFile()
        Sql(path).use { it.execute("PRAGMA user_version=9") }
        val h = EnvironmentalHistory(SqlStoreFactory { Sql(path) })
        assertThrows(IllegalArgumentException::class.java) { h.page() }
        Sql(path).use { assertEquals("9", it.query("PRAGMA user_version")[0][0]) }
    }
    @Test fun actualSqlTransactionRollsBackPartialAssignment() {
        val path = Files.createTempFile("nia-env-rollback", ".db").toFile()
        val h = EnvironmentalHistory(SqlStoreFactory { Sql(path) })
        val b = h.bind(Candidate("test", "bthome-v2", "BTHome v2"), "sensor")
        val e = Equipment(name = "room"); h.saveEquipment(e)
        val p = Position(equipmentId = e.id, name = "shelf"); h.savePosition(p)
        h.assign(b.sensorId, p.id, 100)
        Sql(path).use { it.execute("CREATE TRIGGER fail_new_placement BEFORE INSERT ON entities WHEN NEW.kind='placement' BEGIN SELECT RAISE(ABORT,'injected'); END") }
        assertThrows(Exception::class.java) { h.assign(b.sensorId, p.id, 200) }
        assertTrue(h.entities("placement").single().isNull("until")); h.close()
    }
    @Test fun hundredReportsPerSecondBudgetAndStaleCallbacks() {
        org.junit.Assume.assumeTrue(java.lang.Boolean.getBoolean("nia.environment.benchmark"))
        val h = history()
        h.bind(Candidate("test", "bthome-v2", "BTHome v2"), "fixture sensor")
        val started = CountDownLatch(1)
        lateinit var send: (Advertisement) -> Unit
        val c = EnvironmentalCoordinator(file(), h, { BleTransport { report, _ -> send = report; started.countDown(); object : RadioSession { override fun close() {} } } })
        c.foreground(true); c.enable(); c.start(); assertTrue(started.await(5, TimeUnit.SECONDS))
        val payload = byteArrayOf(0x40, 0, 1, 2, 0x2e, 0xfb.toByte(), 3, 0xd2.toByte(), 4)
        fun report() = send(Advertisement("test", null, emptyMap(), mapOf("0000fcd2-0000-1000-8000-00805f9b34fb" to payload), System.currentTimeMillis(), System.nanoTime()))
        repeat(2000) { report(); if (it % 100 == 0) Thread.sleep(100) }
        Thread.sleep(1000)
        val os = java.lang.management.ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
        fun rss() = File("/proc/self/status").readLines().first { it.startsWith("VmRSS:") }.trim().split(Regex("\\s+"))[1].toLong() * 1024
        val droppedBefore = c.view.value.dropped
        val memoryBefore = rss(); val cpuBefore = os.processCpuTime; val start = System.nanoTime()
        repeat(1000) { i ->
            report()
            val wait = start + (i + 1) * 10_000_000L - System.nanoTime()
            if (wait > 0) java.util.concurrent.locks.LockSupport.parkNanos(wait)
        }
        Thread.sleep(500)
        val elapsed = System.nanoTime() - start
        val cpuPercent = (os.processCpuTime - cpuBefore) * 100.0 / elapsed
        val memoryIncrease = rss() - memoryBefore
        assertTrue(c.view.value.latest.isNotEmpty())
        c.stop(); val latest = h.page().first(); report(); Thread.sleep(100)
        assertEquals(latest, h.page().first())
        println("ENV_BENCHMARK reports_per_second=100 cpu_percent_one_core=$cpuPercent rss_increase_bytes=$memoryIncrease dropped=${c.view.value.dropped - droppedBefore} physical_radio=false")
        assertEquals(droppedBefore, c.view.value.dropped)
        assertTrue("CPU $cpuPercent%", cpuPercent < 5.0)
        assertTrue("RSS increase $memoryIncrease", memoryIncrease < 32L * 1024 * 1024)
        c.close()
    }
}
