package com.nozzleitall.testgrid

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportTest {
    private fun physical(suite: String, preset: SimulatedPrinter.Preset, start: Long, faults: MutableSet<String> = mutableSetOf(), answers: Map<String, String> = emptyMap(),
                         supersedes: List<String> = emptyList(), version: String = "0.1.0"): Pair<String, EvidenceBundle> {
        val clock = Support.Clock(start)
        val target = Support.PhysicalFake(SimulatedPrinter(preset, clock::now, faults))
        val work = Support.tmp()
        val env = Support.env(work, clock, generateSequence(start) { it + 1 }.map { "run-$it" }.iterator(), version)
        val journal = RunJournal(java.io.File(work, "run"))
        val s = RunSession.start(Support.suite(suite), target, TargetCheck.inspect(target), SimulatedSlicer(Support.profiles(), java.io.File(work, "sl")), env, journal, SafetyLevel.PHYSICAL_PRINT, supersedes)
        Support.drive(s, answers)
        return s.record.runId to EvidenceBuilder.build(s, env, Redactor(target.localSecrets()), journal::attachment)
    }

    private fun simulated(suite: String, preset: SimulatedPrinter.Preset, start: Long): EvidenceBundle {
        val clock = Support.Clock(start)
        val st = Support.start(suite, SimulatedPrinter(preset, clock::now), clock)
        Support.drive(st.session)
        return Support.build(st)
    }

    private fun row(r: CompatibilityReport, family: String, scope: MaterialScope = MaterialScope.SINGLE, adapter: String = "android-moonraker") =
        r.rows.single { it.key.firmwareFamily == family && it.key.scope == scope && it.key.adapter == adapter && it.history.isNotEmpty() }

    @Test fun categoriesAreGradedIndependently() {
        // Printing fails (measured out of tolerance) while slicing, transfer, monitoring and controls pass.
        val (_, b) = physical("paxx-u1", SimulatedPrinter.Preset.PAXX_U1, 1_790_000_000_000L, answers = mapOf("tower-x" to "21.3"))
        val r = ReportBuilder.build(listOf("a.zip" to b.zip()), emptyList())
        val g = row(r, FirmwareFamilies.PAXX).current!!.grades
        assertEquals(ResultState.FAIL, g[Category.PHYSICAL_PRINT])
        assertEquals(ResultState.PASS, g[Category.SLICING])
        assertEquals(ResultState.PASS, g[Category.FILE_TRANSFER])
        assertEquals(ResultState.PASS, g[Category.MONITORING])
        assertEquals(ResultState.PASS, g[Category.CONTROLS])
    }

    @Test fun passingOneCategoryNeverFillsAnother() {
        // Only level 0/1 was allowed: slicing and monitoring pass, everything else stays SKIPPED, never PASS.
        val clock = Support.Clock()
        val target = Support.PhysicalFake(SimulatedPrinter(SimulatedPrinter.Preset.PAXX_U1, clock::now))
        val work = Support.tmp(); val env = Support.env(work, clock); val journal = RunJournal(java.io.File(work, "run"))
        val s = RunSession.start(Support.suite("paxx-u1"), target, TargetCheck.inspect(target), SimulatedSlicer(Support.profiles(), work), env, journal, SafetyLevel.READ_ONLY)
        Support.drive(s)
        val b = EvidenceBuilder.build(s, env, Redactor(target.localSecrets()), journal::attachment)
        val g = row(ReportBuilder.build(listOf("a.zip" to b.zip()), emptyList()), FirmwareFamilies.PAXX).current!!.grades
        assertEquals(ResultState.PASS, g[Category.SLICING])
        assertEquals(ResultState.PASS, g[Category.MONITORING])
        // upload-guard (level 1) passed but the level-2 transfer test was skipped: the category is PARTIAL, not PASS.
        assertEquals(ResultState.PARTIAL, g[Category.FILE_TRANSFER])
        assertEquals(ResultState.SKIPPED, g[Category.CONTROLS])
        assertEquals(ResultState.SKIPPED, g[Category.PHYSICAL_PRINT])
    }

    @Test fun firmwareVariantsNeverShareARow() {
        val (_, paxx) = physical("paxx-u1", SimulatedPrinter.Preset.PAXX_U1, 1_790_000_000_000L)
        val (_, stock) = physical("snapmaker-u1-stock", SimulatedPrinter.Preset.STOCK_U1, 1_790_100_000_000L)
        val (_, cosmos) = physical("cosmos-centauri-carbon", SimulatedPrinter.Preset.COSMOS_CC, 1_790_200_000_000L)
        val r = ReportBuilder.build(listOf("paxx.zip" to paxx.zip(), "stock.zip" to stock.zip(), "cosmos.zip" to cosmos.zip()), SuiteCatalog.bundled())
        assertEquals(1, row(r, FirmwareFamilies.PAXX).history.size)
        assertEquals(1, row(r, FirmwareFamilies.SNAPMAKER_STOCK).history.size)
        assertEquals(1, row(r, FirmwareFamilies.COSMOS).history.size)
        // COSMOS evidence says nothing about stock or OpenCentauri-patched Centauri Carbons: they stay listed, UNVERIFIED.
        listOf(FirmwareFamilies.ELEGOO_STOCK, FirmwareFamilies.OPENCENTAURI_PATCHED).forEach { fam ->
            val rows = r.rows.filter { it.key.firmwareFamily == fam }
            assertTrue(fam, rows.isNotEmpty())
            rows.forEach { row -> assertNull(row.current); Category.entries.forEach { c -> assertEquals("UNVERIFIED", ReportBuilder.cell(row, c)) } }
        }
        // A COSMOS run without CANVAS grades multi-material as SKIPPED (hardware not detected), never as a pass.
        val multi = row(r, FirmwareFamilies.COSMOS, MaterialScope.MULTI)
        Category.entries.forEach { assertTrue(it.id, multi.current!!.grades[it] in setOf(ResultState.SKIPPED, ResultState.UNVERIFIED)) }
    }

    @Test fun aNewerRunSupersedesButNeverDeletesHistory() {
        val (oldId, old) = physical("paxx-u1", SimulatedPrinter.Preset.PAXX_U1, 1_790_000_000_000L, answers = mapOf("tower-x" to "21.3"))
        val (newId, new) = physical("paxx-u1", SimulatedPrinter.Preset.PAXX_U1, 1_791_000_000_000L, supersedes = listOf(oldId))
        val r = ReportBuilder.build(listOf("new.zip" to new.zip(), "old.zip" to old.zip()), emptyList())
        val row = row(r, FirmwareFamilies.PAXX)
        assertEquals(newId, row.current!!.runId)
        assertEquals(ResultState.PASS, row.current.grades[Category.PHYSICAL_PRINT])
        assertEquals(2, row.history.size)
        val oldEntry = row.history.single { it.runId == oldId }
        assertEquals(newId, oldEntry.supersededBy)
        assertEquals(ResultState.FAIL, oldEntry.grades[Category.PHYSICAL_PRINT])
        val md = ReportBuilder.markdown(r)
        assertTrue(md.contains(old.bundleDigest)); assertTrue(md.contains(new.bundleDigest))
        assertTrue(md.contains("superseded by"))
        // Implicit supersession by recency, without an explicit link, also keeps the old run.
        val (_, third) = physical("paxx-u1", SimulatedPrinter.Preset.PAXX_U1, 1_792_000_000_000L)
        val r2 = ReportBuilder.build(listOf("a.zip" to old.zip(), "b.zip" to new.zip(), "c.zip" to third.zip()), emptyList())
        assertEquals(3, row(r2, FirmwareFamilies.PAXX).history.size)
    }

    @Test fun differentNozzleBuildsAreSeparateRows() {
        val (_, a) = physical("paxx-u1", SimulatedPrinter.Preset.PAXX_U1, 1_790_000_000_000L, version = "0.1.0")
        val (_, b) = physical("paxx-u1", SimulatedPrinter.Preset.PAXX_U1, 1_791_000_000_000L, version = "0.2.0")
        val r = ReportBuilder.build(listOf("a.zip" to a.zip(), "b.zip" to b.zip()), emptyList())
        assertEquals(setOf("0.1.0", "0.2.0"), r.rows.filter { it.key.firmwareFamily == FirmwareFamilies.PAXX && it.key.scope == MaterialScope.SINGLE }.map { it.key.nozzleVersion }.toSet())
    }

    @Test fun simulatedRunsNeverOutrankOrPretendToBePhysicalEvidence() {
        val (physId, phys) = physical("paxx-u1", SimulatedPrinter.Preset.PAXX_U1, 1_790_000_000_000L)
        val sim = simulated("paxx-u1", SimulatedPrinter.Preset.PAXX_U1, 1_799_000_000_000L)
        val r = ReportBuilder.build(listOf("p.zip" to phys.zip(), "s.zip" to sim.zip()), emptyList())
        val physRow = row(r, FirmwareFamilies.PAXX)
        assertEquals(physId, physRow.current!!.runId)
        val simRow = row(r, FirmwareFamilies.PAXX, adapter = "simulated")
        Category.entries.forEach { assertEquals(ResultState.UNVERIFIED, simRow.current!!.grades[it]) }
        assertTrue(simRow.note.contains("Simulated"))
    }

    @Test fun corruptBundlesAreListedNotCountedAndDuplicatesCountOnce() {
        val (_, b) = physical("paxx-u1", SimulatedPrinter.Preset.PAXX_U1, 1_790_000_000_000L)
        val zip = b.zip()
        val r = ReportBuilder.build(listOf("good.zip" to zip, "copy.zip" to zip, "broken.zip" to zip.copyOf(zip.size / 2)), emptyList())
        assertEquals(1, row(r, FirmwareFamilies.PAXX).history.size)
        assertEquals(listOf("broken.zip"), r.rejected.map { it.first })
        assertEquals(1, r.duplicates.size)
    }

    @Test fun maintainerReviewIsShownAndRejectedBundlesNeverBecomeCurrent() {
        val (_, a) = physical("paxx-u1", SimulatedPrinter.Preset.PAXX_U1, 1_790_000_000_000L)
        val (_, b) = physical("paxx-u1", SimulatedPrinter.Preset.PAXX_U1, 1_791_000_000_000L)
        val unreviewed = ReportBuilder.build(listOf("a.zip" to a.zip()), emptyList())
        assertTrue(ReportBuilder.cell(row(unreviewed, FirmwareFamilies.PAXX), Category.SLICING).contains("unreviewed"))
        val ledger = AcceptanceLedger.parse(JSONObject().put("format", AcceptanceLedger.FORMAT).put("version", JSONArray().put(1).put(0))
            .put("accepted", JSONArray().put(JSONObject().put("bundleDigest", a.bundleDigest).put("acceptedBy", "maintainer").put("date", "2026-09-28")))
            .put("rejected", JSONArray().put(JSONObject().put("bundleDigest", b.bundleDigest).put("reason", "photos do not show this printer"))).toString())
        val r = ReportBuilder.build(listOf("a.zip" to a.zip(), "b.zip" to b.zip()), emptyList(), ledger)
        val row = row(r, FirmwareFamilies.PAXX)
        assertEquals(a.bundleDigest, row.current!!.bundleDigest)
        assertEquals("PASS", ReportBuilder.cell(row, Category.SLICING))
        assertTrue(row.history.single { it.bundleDigest == b.bundleDigest }.review.startsWith("rejected"))
    }

    @Test fun theEvidenceStoreNeverOverwrites() {
        val (_, a) = physical("paxx-u1", SimulatedPrinter.Preset.PAXX_U1, 1_790_000_000_000L)
        val dir = Support.tmp()
        val store = EvidenceStore(dir)
        val added = store.add(a.zip())
        assertTrue(added is EvidenceStore.AddResult.Added)
        added as EvidenceStore.AddResult.Added
        val before = added.file.readBytes()
        assertTrue(store.add(a.zip()) is EvidenceStore.AddResult.AlreadyPresent)
        assertTrue(store.add("garbage".toByteArray()) is EvidenceStore.AddResult.Rejected)
        assertTrue(added.file.readBytes().contentEquals(before))
        assertEquals(1, store.all().size)
    }

    @Test fun reportOutputIsDeterministic() {
        val (_, a) = physical("paxx-u1", SimulatedPrinter.Preset.PAXX_U1, 1_790_000_000_000L)
        val inputs = listOf("a.zip" to a.zip())
        assertEquals(ReportBuilder.markdown(ReportBuilder.build(inputs, SuiteCatalog.bundled())), ReportBuilder.markdown(ReportBuilder.build(inputs.reversed(), SuiteCatalog.bundled())))
        assertEquals(Canon.write(ReportBuilder.json(ReportBuilder.build(inputs, SuiteCatalog.bundled()))), Canon.write(ReportBuilder.json(ReportBuilder.build(inputs, SuiteCatalog.bundled()))))
    }

    @Test fun everyFixtureConfigurationIsListedAsUnverifiedWithoutEvidence() {
        val r = ReportBuilder.build(emptyList(), SuiteCatalog.bundled())
        val families = r.rows.map { it.key.firmwareFamily }.toSet()
        assertTrue(families.containsAll(listOf("paxx-extended", "cosmos", "snapmaker-stock", "elegoo-stock", "opencentauri-patched", "bambu-lan", "prusalink", "klipper", "octoprint")))
        r.rows.forEach { row -> assertNull(row.current); Category.entries.forEach { assertEquals("UNVERIFIED", ReportBuilder.cell(row, it)) } }
        assertNotNull(r.rows.firstOrNull { it.note.contains("fixture") })
    }
}
