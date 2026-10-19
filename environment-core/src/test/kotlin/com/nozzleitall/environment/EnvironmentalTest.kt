package com.nozzleitall.environment

import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.util.concurrent.atomic.AtomicBoolean

class EnvironmentalTest {
    companion object {
        /** Test-only child entry point. Forced process death, never a production helper. */
        @JvmStatic fun main(args: Array<String>) {
            require(args.size == 2)
            val root = File(args[0]).canonicalFile; val phase = args[1]
            require(root.parentFile == File(System.getProperty("java.io.tmpdir")).canonicalFile && root.name.startsWith("nia-env-crash-"))
            require(phase in setOf("intent", "transaction", "purged"))
            val gate = EnvironmentalGate(File(root, "control.json"))
            val history = EnvironmentalHistory(SqlStoreFactory {
                val sql = Sql(File(root, "history.db"))
                object : SqlStore by sql {
                    override fun execute(statement: String, vararg args: Any?) {
                        sql.execute(statement, *args)
                        if (phase == "transaction" && statement == "DELETE FROM events") Runtime.getRuntime().halt(17)
                    }
                }
            })
            gate.deleteIntent()
            println("ENV_CRASH phase=$phase intent_committed=true expected_exit=17")
            if (phase == "intent") Runtime.getRuntime().halt(17)
            history.deleteAll()
            Runtime.getRuntime().halt(17) // Before finishDelete: explicit resume must complete.
        }
    }
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
        override fun <T> transaction(block: () -> T): T {
            c.autoCommit = false
            var failed: Throwable? = null
            try { val v = block(); c.commit(); return v }
            catch (e: Throwable) {
                failed = e
                try { c.rollback() } catch (cleanup: Throwable) { e.addSuppressed(cleanup) }
                statements.values.forEach { s -> try { s.close() } catch (cleanup: Throwable) { e.addSuppressed(cleanup) } }
                statements.clear()
                throw e
            }
            finally { try { c.autoCommit = true } catch (cleanup: Throwable) { if (failed != null) failed.addSuppressed(cleanup) else throw cleanup } }
        }
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
    @Test fun disabledStartupDoesNotInvokeIdentifierOrEntropyProvider() {
        var identifiers = 0
        val path = file()
        val gate = EnvironmentalGate(path, newIdentifier = { identifiers++; newId() })
        assertEquals(EnvironmentalState.DISABLED, gate.state); assertEquals(0, identifiers)
        gate.enable(); assertEquals(2, identifiers) // Only identifiers required by the explicit control write.
        val restored = EnvironmentalGate(path, newIdentifier = { error("Idle restore must not invoke entropy") })
        assertEquals(EnvironmentalState.ENABLED_IDLE, restored.state)
        gate.begin(Purpose.DISCOVERY, emptyList(), 1_000_000_000)
        assertEquals(3, identifiers) // Process-session nonce is lazy until an actual operation.
    }
    @Test fun oversizedControlIsPreservedWithoutInvokingEntropy() {
        val path = file(); path.parentFile.mkdirs(); val original = "x".repeat(5000); path.writeText(original)
        val gate = EnvironmentalGate(path, newIdentifier = { error("No entropy during recovery startup") })
        assertEquals(EnvironmentalState.DISABLED_RECOVERY, gate.state)
        assertEquals(original, path.readText())
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
    @Test fun deleteResumePreservesRecordedIntentAcrossRestart() {
        val path = file(); val first = EnvironmentalGate(path)
        first.enable(); first.deleteIntent()
        val recorded = path.readText(); val incarnation = first.incarnation
        val resumed = EnvironmentalGate(path)
        assertTrue(resumed.deletePending)
        assertThrows(IllegalStateException::class.java) { resumed.revoke() }
        resumed.deleteIntent()
        assertEquals(incarnation, resumed.incarnation)
        assertEquals(recorded, path.readText())
        resumed.finishDelete()
        assertFalse(resumed.deletePending)
        assertEquals(EnvironmentalState.DISABLED, EnvironmentalGate(path).state)
    }
    @Test fun realProcessDeathAtDeleteCrashPointsRollsBackAndResumesExplicitly() {
        for (phase in listOf("intent", "transaction", "purged")) {
            val root = Files.createTempDirectory("nia-env-crash-").toFile()
            val control = File(root, "control.json"); val database = File(root, "history.db")
            val original = EnvironmentalGate(control); original.enable()
            EnvironmentalHistory(SqlStoreFactory { Sql(database) }).use { it.append(observation(incarnation = original.incarnation), original.incarnation) }
            val child = ProcessBuilder(File(System.getProperty("java.home"), "bin/java").absolutePath,
                "-Djava.io.tmpdir=${System.getProperty("java.io.tmpdir")}", "-cp", System.getProperty("nia.environment.testClasspath"), EnvironmentalTest::class.java.name, root.absolutePath, phase)
                .directory(root).redirectErrorStream(true).start()
            val finished = child.waitFor(15, TimeUnit.SECONDS)
            if (!finished) child.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
            assertTrue("Crash child timed out: $phase", finished)
            val output = child.inputStream.bufferedReader().use { it.readText() }
            assertEquals("Expected controlled halt $phase: $output", 17, child.exitValue())
            var opens = 0; var radios = 0
            val h = EnvironmentalHistory(SqlStoreFactory { opens++; Sql(database) })
            val resumed = EnvironmentalCoordinator(control, h, { radios++; error("Crash recovery cannot open radio") })
            assertEquals(EnvironmentalState.DISABLED_RECOVERY, resumed.view.value.state)
            assertEquals(0, opens); assertEquals(0, radios)
            val intent = resumed.gate.incarnation
            assertNotEquals(original.incarnation, intent)
            assertEquals(if (phase == "purged") 0L else 1L, h.eventCount())
            resumed.deleteAll()
            assertEquals(intent, resumed.gate.incarnation)
            assertEquals(EnvironmentalState.DISABLED, EnvironmentalGate(control).state)
            assertEquals(0L, h.eventCount()); assertEquals(0, radios)
            resumed.close()
            println("ENV_CRASH_RECOVERY phase=$phase actual_process_halt=17 rollback_and_explicit_resume=PASS")
        }
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
    @Test fun cumulativeGapUpsertsDoNotLoseOrDoubleCountEarlierBatches() {
        history().use { h ->
            h.appendBatch(listOf(observation().copy(droppedBefore = 10)), "inc")
            assertEquals(10L, h.recordedDrops())
            h.recordGap("session", 1001, 15)
            assertEquals(15L, h.recordedDrops())
            h.recordGap("session", 1001, 15)
            h.recordGap("session", 999, 12)
            assertEquals(15L, h.recordedDrops())
        }
    }
    @Test fun staleIdleStopCannotRevokeAYoungerStart() {
        val h = history(); h.bind(Candidate("test", "bthome-v2", "BTHome v2"), "fixture")
        val ready = CountDownLatch(1); val closed = AtomicBoolean(false)
        val c = EnvironmentalCoordinator(file(), h, { BleTransport { _, _, _ -> ready.countDown(); object : RadioSession { override fun close() { closed.set(true) } } } })
        c.foreground(true); c.enable()
        val stopper = Thread { c.stop() }
        synchronized(c) {
            stopper.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (stopper.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(1)
            assertEquals(Thread.State.BLOCKED, stopper.state)
            c.start()
        }
        stopper.join(5000); assertFalse(stopper.isAlive)
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        assertEquals(EnvironmentalState.COLLECTING, c.view.value.state); assertFalse(closed.get())
        c.stop()
        val closeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!closed.get() && System.nanoTime() < closeDeadline) Thread.sleep(1)
        assertTrue(closed.get()); c.close()
    }
    @Test fun pendingDisableDeniesNewStartBeforeTakingCoordinatorMonitor() {
        val c = EnvironmentalCoordinator(file(), history(), { error("No radio authorized") })
        c.foreground(true); c.enable()
        val stopper = Thread { c.stop(disable = true) }
        synchronized(c) {
            stopper.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (stopper.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(1)
            assertEquals(Thread.State.BLOCKED, stopper.state)
            assertThrows(IllegalStateException::class.java) { c.start() }
        }
        stopper.join(5000); assertFalse(stopper.isAlive)
        assertEquals(EnvironmentalState.DISABLED, c.view.value.state)
        c.enable(); assertEquals(EnvironmentalState.ENABLED_IDLE, c.view.value.state); c.close()
    }
    @Test fun pendingDisableAlsoDeniesEnableWhenAlreadyDisabled() {
        val path = file()
        val c = EnvironmentalCoordinator(path, history(), { error("No radio authorized") })
        c.foreground(true)
        val stopper = Thread { c.stop(disable = true) }
        synchronized(c) {
            stopper.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (stopper.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(1)
            assertEquals(Thread.State.BLOCKED, stopper.state)
            assertThrows(IllegalStateException::class.java) { c.enable() }
            assertFalse(path.exists())
        }
        stopper.join(5000); assertFalse(stopper.isAlive)
        assertEquals(EnvironmentalState.DISABLED, c.view.value.state)
        c.enable(); assertEquals(EnvironmentalState.ENABLED_IDLE, c.view.value.state); c.close()
    }
    @Test fun uiStopCancelsQueuedOlderStartWithoutWaitingForPersistenceMonitor() {
        var opens = 0; var radios = 0; val path = file()
        val c = EnvironmentalCoordinator(path, EnvironmentalHistory(SqlStoreFactory { opens++; error("No history admitted") }), { radios++; error("No radio admitted") })
        c.foreground(true); c.enable(); val before = path.readText()
        lateinit var work: Job; lateinit var cleanup: Job
        synchronized(c) {
            work = c.requestOperationAsync(Purpose.COLLECTION)
            cleanup = c.requestStopAsync(disable = true)
            assertEquals(before, path.readText()) // Main prelude did not persist under the externally held monitor.
            assertThrows(IllegalStateException::class.java) { c.enable() }
            assertThrows(IllegalStateException::class.java) { c.start() }
        }
        runBlocking { withTimeout(5000) { work.join(); cleanup.join() } }
        assertEquals(0, opens); assertEquals(0, radios)
        assertEquals(EnvironmentalState.DISABLED, c.view.value.state)
        c.close()
    }
    @Test fun recoveryDeniesEveryHistoryEntryWithoutOpeningStore() {
        val path = file(); path.parentFile.mkdirs(); path.writeText("corrupt")
        var opens = 0
        val c = EnvironmentalCoordinator(path, EnvironmentalHistory(SqlStoreFactory { opens++; error("Recovery must not open history") }), { error("No radio") })
        assertThrows(EnvironmentalRecoveryException::class.java) { c.loadHistory() }
        assertThrows(EnvironmentalRecoveryException::class.java) { c.savedBindings() }
        assertThrows(EnvironmentalRecoveryException::class.java) { c.metadata("sensor") }
        assertThrows(EnvironmentalRecoveryException::class.java) { c.retention() }
        assertThrows(EnvironmentalRecoveryException::class.java) { c.export {} }
        assertEquals(0, opens); assertEquals("corrupt", path.readText()); c.close()
    }
    @Test fun ownedDeleteFencesHistoryAndEnableBeforeIoCanAcquireMonitor() {
        val path = file(); val h = history()
        val c = EnvironmentalCoordinator(path, h, { error("No radio") }); c.enable()
        lateinit var cleanup: Job
        synchronized(c) {
            cleanup = c.requestDeleteAsync()
            assertThrows(IllegalStateException::class.java) { c.enable() }
            assertThrows(EnvironmentalRecoveryException::class.java) { c.loadHistory() }
            assertThrows(EnvironmentalRecoveryException::class.java) { c.export {} }
        }
        runBlocking { withTimeout(5000) { cleanup.join() } }
        assertEquals(EnvironmentalState.DISABLED, c.view.value.state)
        assertTrue(c.view.value.status.startsWith("Environmental data deleted"))
        assertTrue(c.loadHistory().isEmpty()); c.enable(); c.close()
    }
    @Test fun foregroundReleaseKeepsTheSameCoordinatorReusableWithoutAutoRestart() {
        val starts = java.util.concurrent.atomic.AtomicInteger(); val closes = java.util.concurrent.atomic.AtomicInteger()
        val c = EnvironmentalCoordinator(file(), history(), { BleTransport { _, _, _ ->
            val n = starts.incrementAndGet()
            object : RadioSession { override val adapterDescription = "fixture-$n"; override fun close() { closes.incrementAndGet() } }
        } })
        fun waitUntil(test: () -> Boolean) { val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5); while (!test() && System.nanoTime() < deadline) Thread.sleep(1); assertTrue(test()) }
        c.foreground(true); c.enable(); c.discover(); waitUntil { c.view.value.status.contains("fixture-1") }
        val cleanup = c.releaseForegroundAsync()
        runBlocking { withTimeout(5000) { cleanup?.join() } }
        waitUntil { c.view.value.state == EnvironmentalState.ENABLED_IDLE }
        assertEquals(1, closes.get()); c.foreground(true); Thread.sleep(100); assertEquals(1, starts.get())
        c.discover(); waitUntil { c.view.value.status.contains("fixture-2") }
        c.stop(); assertEquals(2, closes.get()); c.close()
    }
    @Test fun foregroundLossDuringHistoryPreparationCannotProceedToRetentionOrRadio() = checkHistoryPreparationCancellation(false)
    @Test fun ordinaryUiStopDuringHistoryPreparationCannotProceedToRetentionOrRadio() = checkHistoryPreparationCancellation(true)
    private fun checkHistoryPreparationCancellation(stop: Boolean) {
        val path = Files.createTempFile("nia-env-preparation", ".db").toFile()
        val hold = AtomicBoolean(false); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val deletes = java.util.concurrent.atomic.AtomicInteger(); val radios = java.util.concurrent.atomic.AtomicInteger()
        val h = EnvironmentalHistory(SqlStoreFactory {
            val sql = Sql(path)
            object : SqlStore by sql {
                override fun query(statement: String, vararg args: Any?): List<List<String?>> {
                    val result = sql.query(statement, *args)
                    if (args.firstOrNull() == "binding" && hold.getAndSet(false)) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                    return result
                }
                override fun execute(statement: String, vararg args: Any?) { if (statement.startsWith("DELETE FROM")) deletes.incrementAndGet(); sql.execute(statement, *args) }
            }
        })
        h.bind(Candidate("test", "bthome-v2", "BTHome v2"), "fixture")
        val c = EnvironmentalCoordinator(file(), h, { radios.incrementAndGet(); error("No radio authorized") })
        c.foreground(true); c.enable(); hold.set(true)
        val failed = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val starter = Thread { try { c.start() } catch (t: Throwable) { failed.set(t) } }
        try {
            starter.start(); assertTrue(entered.await(5, TimeUnit.SECONDS))
            val cleanup = if (stop) c.requestStopAsync() else { c.foreground(false); null }
            release.countDown(); starter.join(5000)
            runBlocking { withTimeout(5000) { cleanup?.join() } }
            assertFalse(starter.isAlive); assertTrue(failed.get() is IllegalStateException)
            assertEquals(0, deletes.get()); assertEquals(0, radios.get())
            assertEquals(EnvironmentalState.ENABLED_IDLE, c.view.value.state)
        } finally { release.countDown(); starter.join(5000); c.close() }
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
    @Test fun foregroundLossDuringCommittedBatchCannotRepublishLiveReadings() {
        val path = Files.createTempFile("nia-env-held-commit", ".db").toFile()
        val hold = AtomicBoolean(false); val revoked = AtomicBoolean(false); val badPublication = AtomicBoolean(false)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val ready = CountDownLatch(1)
        val h = EnvironmentalHistory(SqlStoreFactory {
            val sql = Sql(path)
            object : SqlStore by sql {
                override fun <T> transaction(block: () -> T): T {
                    val result = sql.transaction(block)
                    if (hold.getAndSet(false)) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                    return result
                }
            }
        })
        h.bind(Candidate("test", "bthome-v2", "BTHome v2"), "fixture")
        lateinit var send: (Advertisement) -> Unit
        val c = EnvironmentalCoordinator(file(), h, { BleTransport { _, report, _ -> send = report; ready.countDown(); object : RadioSession { override fun close() {} } } })
        val observer = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        observer.launch { c.view.collect { if (revoked.get() && it.latest.isNotEmpty()) badPublication.set(true) } }
        try {
            c.foreground(true); c.enable(); c.start(); assertTrue(ready.await(5, TimeUnit.SECONDS))
            hold.set(true)
            send(Advertisement("test", null, emptyMap(), mapOf("0000fcd2-0000-1000-8000-00805f9b34fb" to byteArrayOf(0x40, 2, 0x34, 0x09)), System.currentTimeMillis(), System.nanoTime()))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            revoked.set(true); c.foreground(false)
            assertTrue(c.view.value.latest.isEmpty())
            release.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (c.view.value.state != EnvironmentalState.ENABLED_IDLE && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals(EnvironmentalState.ENABLED_IDLE, c.view.value.state)
            assertFalse("An in-flight commit republished a reading after visibility revocation", badPublication.get())
        } finally { release.countDown(); observer.cancel(); c.close() }
    }
    @Test fun overflowWithoutAnotherValidObservationIsPersistedOnStop() {
        val path = Files.createTempFile("nia-env-tail-gap", ".db").toFile()
        val h = EnvironmentalHistory(SqlStoreFactory { Sql(path) })
        h.bind(Candidate("test", "bthome-v2", "BTHome v2"), "fixture")
        val ready = CountDownLatch(1)
        lateinit var send: (Advertisement) -> Unit
        val c = EnvironmentalCoordinator(file(), h, { BleTransport { _, report, _ -> send = report; ready.countDown(); object : RadioSession { override fun close() {} } } })
        c.foreground(true); c.enable(); c.start(); assertTrue(ready.await(5, TimeUnit.SECONDS))
        synchronized(c) {
            repeat(1000) { send(Advertisement("test", null, emptyMap(), emptyMap(), 1000, 1)) }
        }
        val startStops = CountDownLatch(1)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val stoppers = List(2) { Thread { startStops.await(); try { c.stop() } catch (t: Throwable) { failure.set(t) } } }
        stoppers.forEach { it.start() }; startStops.countDown()
        stoppers.forEach { it.join(5000); assertFalse(it.isAlive) }
        assertNull(failure.get())
        assertEquals(0L, h.eventCount())
        val dropped = h.recordedDrops(); assertTrue(dropped > 0)
        assertEquals(c.view.value.dropped, dropped)
        Sql(path).use { assertEquals("1", it.query("SELECT COUNT(*) FROM gaps").single().single()) }
        EnvironmentalHistory(SqlStoreFactory { Sql(path) }).use { assertEquals(dropped, it.recordedDrops()) }
        c.deleteAll(); assertEquals(0L, c.view.value.dropped)
        c.enable(); c.discover()
        val readyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!c.view.value.status.contains(" · BLE") && System.nanoTime() < readyDeadline) Thread.sleep(1)
        assertTrue(c.view.value.status.contains(" · BLE"))
        send(Advertisement("test", null, emptyMap(), emptyMap(), 1000, 1))
        Thread.sleep(200)
        assertEquals("Deleted overflow counters must not reappear on the next report", 0L, c.view.value.dropped)
        c.stop(); c.close()
        EnvironmentalHistory(SqlStoreFactory { Sql(path) }).use { assertEquals(0L, it.recordedDrops()) }
    }
    @Test fun goveeRoomAssignmentCanCollectWithoutCertificationAndStopsWithoutAutoResume() {
        val h = history()
        val binding = h.bind(Candidate("room-sensor", "govee-ble", "H5100"), "Room sensor")
        val ready = CountDownLatch(1)
        lateinit var report: (Advertisement) -> Unit
        val closed = AtomicBoolean(false)
        val c = EnvironmentalCoordinator(file(), h, { BleTransport { request, callback, _ ->
            assertNull(request.requiredAdapterDescription)
            report = callback; ready.countDown()
            object : RadioSession { override fun close() { closed.set(true) } }
        } })
        try {
            c.foreground(true); c.enable()
            c.configureRoom(binding, "Office", "Shelf")
            assertTrue(c.view.value.status.startsWith("Room assignment saved"))
            assertEquals("Office" to "Shelf", c.view.value.roomLabels[binding.id])
            c.savedBindings()
            assertEquals("Office" to "Shelf", c.view.value.roomLabels[binding.id])
            c.start(); assertTrue(ready.await(5, TimeUnit.SECONDS))
            report(Advertisement("room-sensor", "Govee_H5100", mapOf(0x0001 to byteArrayOf(0, 0, 3, 0x97.toByte(), 0xec.toByte(), 80)), emptyMap(), System.currentTimeMillis(), System.nanoTime()))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (h.eventCount() == 0L && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals(1L, h.eventCount())
            assertEquals(23.5, c.view.value.latest.values.single().readings.first { it.channel == Channel.TEMPERATURE }.value, 0.001)
            assertTrue(h.entities("qualification").isEmpty())
            c.foreground(false); c.foreground(true)
            val stopDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while ((!closed.get() || c.view.value.state != EnvironmentalState.ENABLED_IDLE) && System.nanoTime() < stopDeadline) Thread.sleep(10)
            assertTrue(closed.get()); assertEquals(EnvironmentalState.ENABLED_IDLE, c.view.value.state)
            report(Advertisement("room-sensor", "Govee_H5100", mapOf(0x0001 to byteArrayOf(0, 0, 3, 0x97.toByte(), 0xec.toByte(), 80)), emptyMap(), System.currentTimeMillis(), System.nanoTime()))
            Thread.sleep(250)
            assertEquals(1L, h.eventCount())
        } finally { c.close() }
    }
    @Test fun ordinaryRoomLabelsDoNotWaiveExplicitEnvelopeOrEquipmentGuards() {
        history().use { h ->
            val b = h.bind(Candidate("room-sensor", "govee-ble", "H5110"), "Room")
            val room = Equipment(name = "Room"); h.saveEquipment(room)
            val shelf = Position(equipmentId = room.id, name = "Shelf"); h.savePosition(shelf)
            h.assign(b.sensorId, shelf.id, 1)
            assertEquals(QualificationAdmission(emptySet(), Long.MAX_VALUE), h.assignedQualificationRadios(listOf(b), "any-platform", 2))
            h.savePosition(shelf.copy(minC = 10.0))
            assertThrows(QualificationUnavailableException::class.java) { h.assignedQualificationRadios(listOf(b), "any-platform", 2) }
            h.savePosition(shelf.copy(maxC = 40.0))
            assertThrows(QualificationUnavailableException::class.java) { h.assignedQualificationRadios(listOf(b), "any-platform", 2) }
            h.savePosition(shelf)
            h.saveEquipment(room.copy(type = "dryer"))
            assertThrows(QualificationUnavailableException::class.java) { h.assignedQualificationRadios(listOf(b), "any-platform", 2) }
            h.save("position", shelf.id, h.entities("position").single().put("equipmentId", "missing"))
            assertThrows(QualificationUnavailableException::class.java) { h.assignedQualificationRadios(listOf(b), "any-platform", 2) }
        }
    }
    @Test fun editingRoomLabelDoesNotEraseAnExistingOperatingEnvelope() {
        val h = history()
        val b = h.bind(Candidate("room-sensor", "govee-ble", "H5100"), "Room")
        val room = Equipment(name = "Room"); h.saveEquipment(room)
        val shelf = Position(equipmentId = room.id, name = "Shelf", minC = 10.0, maxC = 40.0)
        h.savePosition(shelf)
        val c = EnvironmentalCoordinator(file(), h, { error("No radio while saving") })
        try {
            c.foreground(true); c.enable(); c.configureRoom(b, "Room", "Shelf")
            val saved = h.entities("position").single()
            assertEquals(10.0, saved.getDouble("minC"), 0.0)
            assertEquals(40.0, saved.getDouble("maxC"), 0.0)
            assertThrows(Exception::class.java) { c.start() }
            c.configureRoom(b, "Renamed Room", "Renamed Shelf")
            val active = h.entities("placement").single { it.isNull("until") }
            val renamed = h.entities("position").single { it.getString("id") == active.getString("positionId") }
            assertEquals(10.0, renamed.getDouble("minC"), 0.0)
            assertEquals(40.0, renamed.getDouble("maxC"), 0.0)
            assertThrows(QualificationUnavailableException::class.java) { c.start() }
            val partial = Position(equipmentId = renamed.getString("equipmentId"), name = "Partial", maxC = 30.0)
            h.savePosition(partial)
            c.configureRoom(b, "Renamed Room", "Partial")
            val target = h.entities("position").single { it.getString("id") == partial.id }
            assertTrue(target.isNull("minC"))
            assertEquals(30.0, target.getDouble("maxC"), 0.0)
            assertThrows(QualificationUnavailableException::class.java) { c.start() }
        } finally { c.close() }
    }
    @Test fun roomSaveCannotReclassifyNonRoomEquipmentOrDuplicateItsName() {
        val h = history()
        val b = h.bind(Candidate("govee", "govee-ble", "H5110"), "Room")
        val dryer = Equipment(name = "Existing equipment", type = "dryer"); h.saveEquipment(dryer)
        val p = Position(equipmentId = dryer.id, name = "Rack"); h.savePosition(p)
        val c = EnvironmentalCoordinator(file(), h, { error("No radio while saving") })
        try {
            c.foreground(true); c.enable()
            assertThrows(QualificationUnavailableException::class.java) { c.configureRoom(b, dryer.name, "Rack") }
            h.assign(b.sensorId, p.id, 1)
            assertThrows(QualificationUnavailableException::class.java) { c.configureRoom(b, "Another room", "Shelf") }
            assertEquals(1, h.entities("equipment").size)
            assertEquals(p.id, h.entities("placement").single().getString("positionId"))
            val placement = h.entities("placement").single()
            h.save("placement", placement.getString("id"), placement.put("positionId", "missing"))
            assertThrows(QualificationUnavailableException::class.java) { c.configureRoom(b, "Another room", "Shelf") }
            assertEquals(1, h.entities("equipment").size)
        } finally { c.close() }
    }
    @Test fun capabilityQualificationRequiresExactCurrentEvidenceAndNeverPromotesUnknownFirmware() {
        history().use { h ->
            val b = h.recordFirmware(h.bind(Candidate("test", "govee-ble", "H5100"), "fixture"), "fixture-firmware1")
            val equipment = Equipment(name = "fixture"); h.saveEquipment(equipment)
            val p = Position(equipmentId = equipment.id, name = "fixture", minC = 10.0, maxC = 40.0); h.savePosition(p)
            h.assign(b.sensorId, p.id, 1)
            val now = 1000L
            assertThrows(Exception::class.java) { h.assignedQualificationRadios(listOf(b), "fixture-platform", now) }
            fun q(channel: Channel) = CapabilityQualification(bindingId = b.id, bindingRevision = b.revision, model = b.model,
                firmware = b.firmware!!, platform = "fixture-platform", radio = "fixture-radio", decoder = BleDecoders.VERSION,
                scanMode = "active", channel = channel, calibrationVersion = 0, minC = 0.0, maxC = 50.0,
                cadenceMinMillis = 1000, cadenceMaxMillis = 10000, verifiedAt = 500, expiresAt = 2000,
                evidence = listOf("identity", "firmware", "thermal", "calibration", "cadence", "coexistence", "ownerApproval").associateWith { "TEST FIXTURE ONLY: $it" })
            h.recordQualification(q(Channel.TEMPERATURE))
            assertThrows(Exception::class.java) { h.assignedQualificationRadios(listOf(b), "fixture-platform", now) }
            val humidity = q(Channel.HUMIDITY); h.recordQualification(humidity)
            assertEquals(QualificationAdmission(setOf("fixture-radio"), 2000), h.assignedQualificationRadios(listOf(b), "fixture-platform", now))
            assertThrows(Exception::class.java) { h.assignedQualificationRadios(listOf(b), "different-platform", now) }
            assertThrows(Exception::class.java) { h.assignedQualificationRadios(listOf(b), "fixture-platform", 2000) }
            assertThrows(IllegalArgumentException::class.java) { h.recordQualification(humidity.copy(evidence = emptyMap())) }
            h.calibrate(b, Channel.HUMIDITY, 1.0)
            assertThrows(Exception::class.java) { h.assignedQualificationRadios(listOf(b), "fixture-platform", now) }
            h.recordQualification(humidity.copy(calibrationVersion = 1))
            assertEquals(setOf("fixture-radio"), h.assignedQualificationRadios(listOf(b), "fixture-platform", now).radios)
            h.recordQualification(humidity.copy(calibrationVersion = 1, archived = true))
            assertThrows(Exception::class.java) { h.assignedQualificationRadios(listOf(b), "fixture-platform", now) }
            val changed = h.recordFirmware(b, "fixture-firmware2")
            assertThrows(Exception::class.java) { h.assignedQualificationRadios(listOf(changed), "fixture-platform", now) }
            val unknown = h.recordFirmware(changed, "unknown")
            assertThrows(IllegalArgumentException::class.java) { h.recordQualification(humidity.copy(bindingRevision = unknown.revision, firmware = "unknown")) }
        }
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
    @Test fun staleArchiveCannotResurrectADeletedBinding() {
        history().use { h ->
            val old = h.bind(Candidate("fixture", "govee-ble", "H5100"), "fixture")
            h.deleteAll()
            assertThrows(IllegalArgumentException::class.java) { h.archive(old) }
            assertTrue(h.bindings().isEmpty()); assertTrue(h.entities("sensor").isEmpty())
        }
    }
}
