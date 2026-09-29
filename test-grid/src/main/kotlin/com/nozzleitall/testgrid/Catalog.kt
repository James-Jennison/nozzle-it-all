package com.nozzleitall.testgrid

import org.json.JSONObject
import java.io.File

/** The suites bundled with this build, listed in testgrid/suites/index.json (resource directories can't be listed on Android). */
object SuiteCatalog {
    const val DIR = "testgrid/suites/"

    fun bundled(): List<Suite> {
        val loader = SuiteCatalog::class.java.classLoader
        fun res(name: String) = loader.getResourceAsStream(DIR + name)?.use { String(it.readBytes(), Charsets.UTF_8) }
        val index = JSONObject(res("index.json") ?: throw IllegalStateException("testgrid/suites/index.json is not on the classpath."))
        return index.strings("suites").map { f -> Suite.parse(res(f) ?: throw IllegalStateException("Suite $f is listed but missing.")) }
    }

    fun bundled(id: String): Suite? = bundled().firstOrNull { it.id == id }

    /** Suites whose target this printer could be. Firmware is confirmed later, from a live read, before a run starts. */
    fun forKind(suites: List<Suite>, kind: net.jamesjennison.klippercompanion.PrinterKind): List<Suite> =
        suites.filter { it.target.printerKinds.isEmpty() || kind.name in it.target.printerKinds }
}

/**
 * Printable instructions for running a suite by hand. Generated from the suite itself so they can't drift from what
 * Test Mode will actually ask for.
 */
object ManualInstructions {
    fun markdown(suite: Suite, models: ModelLibrary?): String = buildString {
        appendLine("# ${suite.title}")
        appendLine()
        appendLine("Suite `${suite.id}` version ${suite.version} (${suite.coverage.id}), for ${suite.target.manufacturer} ${suite.target.model} on `${suite.target.firmwareFamily}` firmware")
        appendLine("through the `${suite.target.adapter}` adapter. Needs Nozzle It All ${suite.requiredNozzleVersion} or newer. Suite digest `${suite.digest}`.")
        appendLine()
        if (suite.description.isNotBlank()) { appendLine(suite.description); appendLine() }
        if (suite.target.notEquivalentTo.isNotEmpty()) { appendLine("A result from this suite is **not** evidence for: ${suite.target.notEquivalentTo.joinToString(", ") { "`$it`" }}."); appendLine() }
        appendLine("## Before you start")
        appendLine()
        appendLine("- Run this only on a printer you own, with you standing next to it for anything at level 3 or 4.")
        appendLine("- Test Mode shows every action and the target printer before it asks you to approve it. Approve one step at a time; decline anything you are not sure about.")
        appendLine("- If Test Mode says a result is **unknown**, stop. Look at the printer, then record what you found. Nothing is sent again automatically.")
        appendLine("- Emergency stop stays on the printer's own screen and on Nozzle's dashboard. Interrupting a test in Test Mode stops further steps; it does not stop a running print.")
        appendLine("- Highest safety level in this suite: ${suite.maxSafetyLevel.label}. You choose the highest level to allow when you start.")
        appendLine()
        suite.tests.forEachIndexed { i, t ->
            appendLine("## ${i + 1}. ${t.title} (`${t.id}`)")
            appendLine()
            appendLine("Category: **${t.category.label}** · Scope: ${t.scope.label} · ${t.safetyLevel.label}" +
                (if (t.mutatesPrinterState) " · changes printer state" else " · does not change printer state") +
                (if (t.requiresOperatorConfirmation) " · needs your approval per step" else ""))
            if (t.dependsOn.isNotEmpty()) appendLine("Runs only if ${t.dependsOn.joinToString { "`$it`" }} passed.")
            if (t.requiredHardware.isNotEmpty()) appendLine("Needs detected hardware: ${t.requiredHardware.joinToString()}. Skipped otherwise.")
            if (t.description.isNotBlank()) { appendLine(); appendLine(t.description) }
            if (t.preconditions.isNotEmpty()) { appendLine(); appendLine("Preconditions:"); t.preconditions.forEach { appendLine("- [ ] ${it.text}${it.check?.let { c -> " (also checked automatically: $c)" } ?: ""}") } }
            appendLine(); appendLine("Steps:")
            t.steps.forEachIndexed { j, s -> appendLine("${j + 1}. ${describe(s, models)}") }
            if (t.expectedObservations.isNotEmpty()) { appendLine(); appendLine("Expected:"); t.expectedObservations.forEach { appendLine("- $it") } }
            if (t.requiredEvidence.isNotEmpty()) { appendLine(); appendLine("Evidence to collect:"); t.requiredEvidence.forEach { appendLine("- ${it.kind}: ${it.description}") } }
            if (t.cleanup.isNotEmpty()) { appendLine(); appendLine("Cleanup (offered even if the test fails; each step needs approval):"); t.cleanup.forEach { appendLine("- ${describe(it, models)}") } }
            t.timeoutSeconds?.let { appendLine(); appendLine("Time limit: ${it / 60} min; if exceeded the test is recorded as ${t.onTimeout.id.replace('_', ' ')}.") }
            appendLine()
        }
        appendLine("## Afterwards")
        appendLine()
        appendLine("Open **Review evidence**, read every file Test Mode will export, then **Export**. Send the bundle to the maintainers as agreed.")
        appendLine("The bundle contains no printer address, credentials, camera URLs, hostnames, file paths or photo metadata; check the preview anyway.")
    }

    /** One step in plain words, as the plan preview and the printed instructions show it. */
    fun describe(s: Step, models: ModelLibrary?): String {
        val p = s.params
        val approve = if (s.kind?.consequential == true) "**Approve:** " else ""
        val text = when (s.kind) {
            StepKind.VERIFY_MODEL -> "Nozzle checks the acceptance model `${p.optString("model")}` against its published SHA-256."
            StepKind.SLICE -> "Nozzle slices `${p.optString("model")}` with the bundled `${p.optString("profile")}` profile on this device." +
                (models?.let { m -> runCatching { m.entry(p.optString("model")).parts.joinToString { "${it.file} (${it.sha256.take(12)}…)" } }.getOrNull()?.let { " Files: $it." } } ?: "")
            StepKind.SCAN_GCODE -> "Nozzle checks the sliced G-code: " + (0 until (p.optJSONArray("checks")?.length() ?: 0)).joinToString { p.getJSONArray("checks").getJSONObject(it).optString("check").replace('_', ' ') } + "."
            StepKind.UPLOAD_GUARD -> "Nozzle hands the known-unsafe fixture `${p.optString("fixture")}` to the upload path and expects it to be refused before anything is sent."
            StepKind.READ_IDENTITY -> "Nozzle reads the firmware identity (read-only)."
            StepKind.PROFILE_MATCH -> "Nozzle checks that profile `${p.optString("profile")}` suits this printer's live firmware."
            StepKind.READ_STATUS -> "Nozzle reads status and temperatures (read-only)."
            StepKind.CHECK_CAPABILITIES -> "Nozzle checks the declared capabilities."
            StepKind.LIST_CAMERAS -> "Nozzle lists cameras (URLs are not recorded)."
            StepKind.CAMERA_SNAPSHOT -> "Nozzle takes one camera snapshot and records only its size and hash."
            StepKind.LIST_FILES -> "Nozzle lists the printer's G-code files."
            StepKind.READ_MATERIAL_SLOTS -> "Nozzle reads the material slots (read-only)."
            StepKind.MONITOR -> "Nozzle watches status until `${p.optString("until")}`${p.optString("heater").takeIf { it.isNotBlank() }?.let { " ($it ${p.optInt("celsius")} °C)" } ?: ""}, up to ${s.timeoutSeconds ?: 60} s."
            StepKind.UPLOAD -> "Upload the sliced G-code under a unique `nozzle-testgrid-…` name; Nozzle verifies it by SHA-256. Nothing prints."
            StepKind.DELETE_UPLOADED -> "Delete the file this run uploaded (only that file)."
            StepKind.DELETE_LEFTOVERS -> "Delete earlier `nozzle-testgrid-…` files that the printer no longer has loaded; Nozzle shows their names first. The file still loaded from the last print stays."
            StepKind.SET_TEMPERATURE -> ControlAction.SetTemperature(p.optString("heater"), p.optInt("celsius")).describe() + "."
            StepKind.HOME -> ControlAction.Home.describe()
            StepKind.JOG -> ControlAction.Jog(p.optString("axis").uppercase(), p.optDouble("mm")).describe() + "."
            StepKind.START_PRINT -> "Start printing the uploaded file. The printer heats, moves and extrudes."
            StepKind.PAUSE -> ControlAction.Pause.describe() + "."
            StepKind.RESUME -> ControlAction.Resume.describe()
            StepKind.CANCEL -> ControlAction.Cancel.describe()
            StepKind.OBSERVE -> "**You answer:** ${p.optString("question")}" + when (p.optString("response")) {
                "number" -> " (number${p.optString("unit").takeIf { it.isNotBlank() }?.let { " in $it" } ?: ""}; accepted ${s.expect.opt("min") ?: "-∞"} to ${s.expect.opt("max") ?: "∞"})"
                "yes_no" -> " (yes/no)"; "pass_partial_fail" -> " (pass/partial/fail)"; "choice" -> " (${p.strings("choices").joinToString(" / ")})"; else -> ""
            }
            StepKind.ATTACH -> "**You attach:** ${p.optString("prompt").ifBlank { "the required photo or file" }} (evidence `${p.optString("evidence")}`)."
            null -> "Unsupported step kind `${s.kindId}` (needs a newer Nozzle It All)."
        }
        return approve + (if (s.title.isNotBlank() && s.kind?.operator != true) "${s.title}: " else "") + text
    }
}

/**
 * The seam for a future hosted coordinator (distributing suites to invited testers, collecting bundles). Nothing in
 * the core depends on it: Test Mode and the report work entirely from local files. A hosted implementation must not
 * be added until the local workflow and evidence format are validated (docs/testgrid/ARCHITECTURE.md).
 */
interface TestGridCoordinator {
    /** Suites this tester is invited to run. */
    fun availableSuites(): List<Suite>
    /** Hands over an exported bundle. The coordinator verifies it itself (BundleReader); it never trusts the client. */
    fun submit(bundleZip: ByteArray): SubmissionReceipt
}

data class SubmissionReceipt(val accepted: Boolean, val bundleDigest: String?, val message: String)

/** The only implementation today: a folder of suites in, a folder of bundles out (an [EvidenceStore]). */
class LocalDirectoryCoordinator(private val suitesDir: File, private val outbox: EvidenceStore) : TestGridCoordinator {
    override fun availableSuites(): List<Suite> = suitesDir.listFiles { f -> f.name.endsWith(".json") && f.name != "index.json" }?.sortedBy { it.name }?.map { Suite.parse(it.readText()) } ?: emptyList()
    override fun submit(bundleZip: ByteArray): SubmissionReceipt = when (val r = outbox.add(bundleZip)) {
        is EvidenceStore.AddResult.Added -> SubmissionReceipt(true, r.file.name.substringBefore('.'), "Stored ${r.file.name}.")
        is EvidenceStore.AddResult.AlreadyPresent -> SubmissionReceipt(true, r.file.name.substringBefore('.'), "Already stored.")
        is EvidenceStore.AddResult.Rejected -> SubmissionReceipt(false, null, r.reasons.joinToString("; "))
    }
}
