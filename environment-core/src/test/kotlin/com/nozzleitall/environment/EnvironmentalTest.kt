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
        private val statements = linkedMapOf<String, java.sql.PreparedStatement>()
        private fun statement(sql: String, args: Array<out Any?>): java.sql.PreparedStatement {
            if (statements.size >= 32 && sql !in statements) { val first = statements.keys.first(); statements.remove(first)!!.close() }
            return statements.getOrPut(sql) { c.prepareStatement(sql) }.also { s -> s.clearParameters(); args.forEachIndexed { i, a -> s.setObject(i + 1, a) } }
        }
        override fun execute(sql: String, vararg args: Any?) {
            if (sql == "VACUUM") { statements.values.forEach { it.close() }; statements.clear() }
            if (sql.startsWith("PRAGMA") || sql == "VACUUM") c.prepareStatement(sql).use { s -> args.forEachIndexed { i, a -> s.setObject(i + 1, a) }; s.execute() }
            else { val s = statement(sql, args); if (s.execute()) s.resultSet?.close() }
        }
        override fun query(sql: String, vararg args: Any?): List<List<String?>> = statement(sql, args).executeQuery().use { r -> buildList { while (r.next()) add((1..r.metaData.columnCount).map { r.getString(it) }) } }
        override fun <T> transaction(block: () -> T): T { c.autoCommit = false; try { val v = block(); c.commit(); return v } catch (e: Throwable) { c.rollback(); throw e } finally { c.autoCommit = true } }
        override fun close() { statements.values.forEach { it.close() }; c.close() }
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
            assertEquals(3, exported.size); assertEquals(1001L, JSONObject(exported.last()).getLong("receivedAt"))
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
        val c = EnvironmentalCoordinator(file(), history(), { BleTransport { _, _, _ -> entered.countDown(); release.await(5, TimeUnit.SECONDS); object : RadioSession { override fun close() { closed.countDown() } } } })
        c.foreground(true); c.enable(); c.discover(); assertTrue(entered.await(5, TimeUnit.SECONDS)); c.foreground(false); release.countDown()
        assertTrue(closed.await(5, TimeUnit.SECONDS))
        val deadline = System.nanoTime() + 5_000_000_000L
        while (c.view.value.state != EnvironmentalState.ENABLED_IDLE && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals(EnvironmentalState.ENABLED_IDLE, c.view.value.state); c.close()
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
        val c = EnvironmentalCoordinator(file(), h, { BleTransport { _, report, _ -> send = report; started.countDown(); object : RadioSession { override fun close() {} } } })
        c.foreground(true); c.enable(); c.start(); assertTrue(started.await(5, TimeUnit.SECONDS))
        val payload = byteArrayOf(0x40, 0, 1, 2, 0x2e, 0xfb.toByte(), 3, 0xd2.toByte(), 4)
        fun report() = send(Advertisement("test", null, emptyMap(), mapOf("0000fcd2-0000-1000-8000-00805f9b34fb" to payload), System.currentTimeMillis(), System.nanoTime()))
        repeat(2000) { report(); if (it % 100 == 0) Thread.sleep(100) }
        Thread.sleep(1000)
        val os = java.lang.management.ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
        fun rss() = File("/proc/self/status").readLines().first { it.startsWith("VmRSS:") }.trim().split(Regex("\\s+"))[1].toLong() * 1024
        // Separately report a fixed 15s JIT stabilization phase at the same 100Hz rate;
        // do not mislabel cold/JIT process CPU as steady application-thread CPU.
        val warmCpu = os.processCpuTime; val warmStart = System.nanoTime()
        repeat(1500) { i ->
            report()
            val wait = warmStart + (i + 1) * 10_000_000L - System.nanoTime()
            if (wait > 0) java.util.concurrent.locks.LockSupport.parkNanos(wait)
        }
        println("ENV_WARMUP reports_per_second=100 cpu_percent_one_core=${(os.processCpuTime - warmCpu) * 100.0 / (System.nanoTime() - warmStart)} includes_jit=true")
        Thread.sleep(300)
        val eventsBefore = h.eventCount()
        val droppedBefore = c.view.value.dropped
        val memoryBefore = rss(); val cpuBefore = os.processCpuTime; val start = System.nanoTime()
        var peakMemory = memoryBefore
        repeat(3000) { i ->
            report()
            if (i % 100 == 0) peakMemory = maxOf(peakMemory, rss())
            val wait = start + (i + 1) * 10_000_000L - System.nanoTime()
            if (wait > 0) java.util.concurrent.locks.LockSupport.parkNanos(wait)
        }
        Thread.sleep(500)
        val elapsed = System.nanoTime() - start
        val cpuPercent = (os.processCpuTime - cpuBefore) * 100.0 / elapsed
        val memoryIncrease = maxOf(peakMemory, rss()) - memoryBefore
        assertTrue(c.view.value.latest.isNotEmpty())
        assertEquals(eventsBefore + 3000, h.eventCount())
        c.stop(); val latest = h.page().first(); report(); Thread.sleep(100)
        assertEquals(latest, h.page().first())
        println("ENV_BENCHMARK reports_per_second=100 cpu_percent_one_core=$cpuPercent rss_increase_bytes=$memoryIncrease dropped=${c.view.value.dropped - droppedBefore} physical_radio=false")
        assertEquals(droppedBefore, c.view.value.dropped)
        assertTrue("CPU $cpuPercent%", cpuPercent < 5.0)
        assertTrue("RSS increase $memoryIncrease", memoryIncrease < 32L * 1024 * 1024)
        c.close()
    }
    @Test fun failedDurableDeleteIntentCannotPurgeHistoryAndResumeIsExplicit() {
        org.junit.Assume.assumeTrue(System.getProperty("os.name").contains("Linux"))
        val control = file(); val h = history()
        val c = EnvironmentalCoordinator(control, h, { error("No radio authorized") })
        c.enable(); h.append(observation(), "inc")
        Files.setPosixFilePermissions(control.parentFile.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("r-x------"))
        // persist() restores directory owner permissions, so block its target with a test-owned directory instead.
        Files.setPosixFilePermissions(control.parentFile.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
        val preserved = File(control.parentFile, "preserved-control"); Files.move(control.toPath(), preserved.toPath())
        control.mkdir()
        assertThrows(Exception::class.java) { c.deleteAll() }
        assertEquals(1, h.page().size)
        assertTrue(c.view.value.status.contains("Cleanup incomplete"))
        assertTrue(control.delete()) // only the empty test-owned fault-injection directory
        c.deleteAll(); assertTrue(h.page().isEmpty()); assertEquals(EnvironmentalState.DISABLED, c.view.value.state)
        c.close()
    }
    @Test fun radioCallbacksNeverWaitForCoordinatorMonitor() {
        val ready = CountDownLatch(1)
        lateinit var failure: (String) -> Unit
        lateinit var report: (Advertisement) -> Unit
        val c = EnvironmentalCoordinator(file(), history(), { BleTransport { _, r, f -> report = r; failure = f; ready.countDown(); object : RadioSession { override fun close() {} } } })
        c.foreground(true); c.enable(); c.discover(); assertTrue(ready.await(5, TimeUnit.SECONDS))
        synchronized(c) {
            val finished = CountDownLatch(1)
            Thread { failure("Injected radio unavailable"); report(Advertisement("test", null, emptyMap(), emptyMap(), 0, 0)); finished.countDown() }.start()
            assertTrue("OS callback blocked on coordinator lock", finished.await(1, TimeUnit.SECONDS))
        }
        c.close()
    }
    @Test fun stableEncodingPreservesTypesAndEscapesAllControlCharacters() {
        val text = "quote\" slash\\ newline\n tab\t" + (0..31).map { it.toChar() }.joinToString("") + " °C"
        val observation = observation(text)
        val encoded = ObservationEncoding.encode(observation)
        val parsed = JSONObject(encoded)
        assertEquals(text, parsed.getString("eventId")); assertTrue(parsed.isNull("measuredAt")); assertEquals(1L, parsed.getLong("sequence"))
        assertEquals(23.0, parsed.getJSONArray("readings").getJSONObject(0).getDouble("raw"), 0.0)
        assertEquals(encoded, ObservationEncoding.encode(observation.copy(receivedAt = 99)))
    }
    @Test fun batchGapCountsSurviveRestartAndExport() {
        val path = Files.createTempFile("nia-env-gaps", ".db").toFile()
        val h = EnvironmentalHistory(SqlStoreFactory { Sql(path) })
        h.appendBatch(listOf(observation().copy(droppedBefore = 31)), "inc")
        h.close()
        EnvironmentalHistory(SqlStoreFactory { Sql(path) }).use { reopened ->
            assertEquals(31L, reopened.recordedDrops())
            val output = mutableListOf<String>(); reopened.export { output += it }
            assertEquals(31L, JSONObject(output[1]).getLong("knownDroppedReports"))
        }
    }
    @Test fun missingPlatformDependencyStopsInsteadOfLeavingDiscoveryActive() {
        val c = EnvironmentalCoordinator(file(), history(), { BleTransport { _, _, _ -> throw NoClassDefFoundError("fixture dependency") } })
        c.foreground(true); c.enable(); c.discover()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (c.view.value.state != EnvironmentalState.ENABLED_IDLE && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(EnvironmentalState.ENABLED_IDLE, c.view.value.state)
        assertTrue(c.view.value.status.contains("dependency missing"))
        c.close()
    }
    @Test fun fullBudgetRollsBackAndRetentionReusesFreedPages() {
        val path = Files.createTempFile("nia-env-full", ".db").toFile()
        EnvironmentalHistory(SqlStoreFactory { Sql(path) }, EnvironmentalLimits(historyBytes = 1024 * 1024)).use { h ->
            var full = false
            for (i in 0 until 3000) {
                try { h.append(observation("full-$i"), "inc") }
                catch (e: java.sql.SQLException) { assertEquals(13, e.errorCode); full = true; break }
            }
            assertTrue("fixture must reach SQLITE_FULL", full)
            assertTrue(path.length() <= 1024 * 1024)
            assertTrue(h.eventCount() > 0)
            h.retain(31 * 86400000L)
            assertEquals(0L, h.eventCount())
            assertEquals("inserted", h.append(observation("after-retention"), "inc"))
        }
    }
}
