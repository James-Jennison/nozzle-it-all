package net.jamesjennison.klippercompanion.testgrid

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nozzleitall.testgrid.ManualInstructions
import com.nozzleitall.testgrid.ModelLibrary
import com.nozzleitall.testgrid.Pending
import com.nozzleitall.testgrid.ResultState
import com.nozzleitall.testgrid.SafetyLevel
import com.nozzleitall.testgrid.SimulatedPrinter
import com.nozzleitall.testgrid.TargetKind
import net.jamesjennison.klippercompanion.PrinterProfile

/**
 * Test Mode: runs a Nozzle Test Grid suite against one printer and exports redacted evidence. Opened from Settings as
 * a separate full-screen window, so it never touches the dashboard's own connection, pending commands or selection.
 * Every consequential step is shown with its exact action and target and must be approved on its own.
 */
@Composable
fun TestModeScreen(profiles: List<PrinterProfile>, close: () -> Unit) {
    val context = LocalContext.current
    val controller = remember { TestModeController.shared(context) }
    controller.savedProfiles = { profiles }
    val state by controller.state.collectAsState()
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize().testTag("test-mode"), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("TEST MODE · Nozzle Test Grid", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.testTag("test-mode-banner"))
                            Text("Hardware acceptance. Nothing changes a printer until you approve that exact step.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                        }
                        TextButton(close, modifier = Modifier.testTag("test-mode-close")) { Text("Close") }
                    }
                }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("test-mode-busy"))
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp).testTag("test-mode-error")) }
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text(" ") }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            when (state.phase) {
                                TestModePhase.SELECT_TARGET -> TargetStep(controller, state)
                                TestModePhase.SELECT_SUITE -> SuiteStep(controller, state)
                                TestModePhase.REVIEW_PLAN -> PlanStep(controller, state)
                                TestModePhase.RUNNING -> RunStep(controller, state)
                                TestModePhase.REVIEW_EVIDENCE -> EvidenceStep(controller, state, close)
                            }
                        }
                    }
                    item { Text(" ") }
                }
            }
        }
    }
}

@Composable private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); content() } }
}

private fun kindLabel(k: net.jamesjennison.klippercompanion.PrinterKind) = when (k) {
    net.jamesjennison.klippercompanion.PrinterKind.GENERIC_KLIPPER -> "Klipper / Moonraker"
    net.jamesjennison.klippercompanion.PrinterKind.SNAPMAKER_U1_PAXX -> "Snapmaker U1 (PAXX)"
    net.jamesjennison.klippercompanion.PrinterKind.SNAPMAKER_U1 -> "Snapmaker U1 (stock)"
    net.jamesjennison.klippercompanion.PrinterKind.BAMBU_LAB -> "Bambu Lab (LAN)"
    net.jamesjennison.klippercompanion.PrinterKind.PRUSA_LINK -> "PrusaLink"
    net.jamesjennison.klippercompanion.PrinterKind.OCTOPRINT -> "OctoPrint"
    net.jamesjennison.klippercompanion.PrinterKind.ELEGOO -> "Elegoo (LAN)"
}

private fun familyLabel(f: String) = when (f) {
    com.nozzleitall.testgrid.FirmwareFamilies.PAXX -> "PAXX extended firmware"
    com.nozzleitall.testgrid.FirmwareFamilies.SNAPMAKER_STOCK -> "Snapmaker stock firmware"
    com.nozzleitall.testgrid.FirmwareFamilies.COSMOS -> "OpenCentauri COSMOS"
    com.nozzleitall.testgrid.FirmwareFamilies.KLIPPER -> "Klipper"
    com.nozzleitall.testgrid.FirmwareFamilies.ELEGOO_STOCK -> "Elegoo stock firmware"
    com.nozzleitall.testgrid.FirmwareFamilies.OPENCENTAURI_PATCHED -> "OpenCentauri-patched stock firmware"
    com.nozzleitall.testgrid.FirmwareFamilies.BAMBU -> "Bambu Lab LAN mode"
    com.nozzleitall.testgrid.FirmwareFamilies.PRUSALINK -> "PrusaLink"
    com.nozzleitall.testgrid.FirmwareFamilies.OCTOPRINT -> "OctoPrint"
    else -> f
}

private val CAPABILITY_WORDS = linkedMapOf(
    "upload_job" to "upload files", "upload_and_start" to "send and print", "start_print" to "start prints",
    "pause_print" to "pause", "resume_print" to "resume", "cancel_print" to "cancel", "temperatures" to "set temperatures",
    "motion" to "move and home", "camera" to "camera", "files" to "list files", "material_state" to "read loaded materials",
    "multi_material" to "multi-material", "firmware_identity" to "read firmware", "status" to "status and temperatures")

private val HARDWARE_WORDS = mapOf("multi_tool" to "four toolheads", "canvas" to "CANVAS (4 lanes)", "extended_firmware" to "PAXX extended config")

/** A tappable full-width row with a radio mark: the pattern for every one-of-many choice in Test Mode. */
@Composable private fun ChoiceRow(selected: Boolean, title: String, detail: String?, tag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 6.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, null)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable private fun TargetStep(c: TestModeController, s: TestModeState) {
    if (s.resumable) Section("An unfinished run is saved") {
        Text("Resuming never repeats a command: one that was in flight when Nozzle stopped is recorded as an unknown outcome for you to check.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ c.resume() }, modifier = Modifier.testTag("resume-run")) { Text("Resume run") }
            OutlinedButton({ c.discardSaved() }, modifier = Modifier.testTag("discard-run")) { Text("Discard it") }
        }
    }
    val options = c.targets
    val saved = options.withIndex().filter { it.value is TargetOption.Saved }
    val simulated = options.withIndex().filter { it.value is TargetOption.Simulated }
    var showSimulated by remember { mutableStateOf(s.target is TargetOption.Simulated || saved.isEmpty()) }
    Section("1. Which printer?") {
        if (saved.isEmpty()) Text("No printers are saved in this app yet. Add one from the dashboard, or practise with a simulated printer.", style = MaterialTheme.typography.bodySmall)
        saved.forEach { (i, t) ->
            val p = (t as TargetOption.Saved).profile
            ChoiceRow(s.target == t, p.label, kindLabel(p.kind) + (p.slicingModel?.let { m -> " · " + net.jamesjennison.klippercompanion.SlicingModelCatalog.info(m).label } ?: ""), "target-$i") { c.selectTarget(t) }
        }
        TextButton({ showSimulated = !showSimulated }, modifier = Modifier.testTag("show-simulated")) {
            Text(if (showSimulated) "Hide simulated printers" else "Practise with a simulated printer")
        }
        if (showSimulated) {
            Text("A simulated printer runs the whole flow on this phone. Its results are marked simulated and never count as evidence.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            simulated.forEach { (i, t) ->
                val preset = (t as TargetOption.Simulated).preset
                ChoiceRow(s.target == t, "${preset.manufacturer} ${preset.model}", "Simulated · " + familyLabel(com.nozzleitall.testgrid.FirmwareFamilies.classify(
                    SimulatedPrinter(preset).description, SimulatedPrinter(preset).identity()).family) + if (preset.afc) " · CANVAS" else "", "target-$i") { c.selectTarget(t) }
            }
        }
    }
    if (TestModeController.needsDeclaration(s.target)) Section("Which firmware does this Centauri Carbon run?") {
        Text("Elegoo's LAN protocol doesn't report whether the firmware is stock or OpenCentauri-patched. They are graded separately.", style = MaterialTheme.typography.bodySmall)
        TestModeController.ELEGOO_DECLARABLE.forEach { f -> ChoiceRow(s.declaredFamily == f, familyLabel(f), null, "declare-$f") { s.target?.let { c.selectTarget(it, f) } } }
    }
    if (s.target == null) return
    val snap = s.snapshot
    Section("2. Is this the right printer?") {
        if (snap == null) { Text(if (s.busy) "Checking the printer…" else "Couldn't read the printer.", style = MaterialTheme.typography.bodyMedium); return@Section }
        val ok = snap.problems.isEmpty()
        Text("${snap.description.manufacturer} ${snap.description.model}", style = MaterialTheme.typography.titleMedium)
        Text(familyLabel(snap.classified.family) + (snap.identity?.firmware?.version?.takeIf { it.isNotBlank() }?.let { " $it" } ?: "") + if (ok) "  ✓ identified" else "",
            style = MaterialTheme.typography.bodyMedium, color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, modifier = Modifier.testTag("target-firmware"))
        snap.status?.let { st -> Text("${st.state.replaceFirstChar { it.uppercase() }}${if (st.ready) "" else " (not ready)"} · nozzle ${st.nozzle?.let { "%.0f".format(it) } ?: "—"} °C · bed ${st.bed?.let { "%.0f".format(it) } ?: "—"} °C",
            style = MaterialTheme.typography.bodySmall) } ?: Text("Status couldn't be read.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        snap.classified.hardware.filterValues { it }.keys.mapNotNull { HARDWARE_WORDS[it] }.takeIf { it.isNotEmpty() }?.let { Text("Detected: ${it.joinToString(", ")}", style = MaterialTheme.typography.bodySmall) }
        snap.problems.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        var details by remember(snap) { mutableStateOf(false) }
        TextButton({ details = !details }, modifier = Modifier.testTag("target-details")) { Text(if (details) "Hide details" else "Details") }
        if (details) {
            Text("Connection: ${snap.description.adapter} (${snap.description.protocol})", style = MaterialTheme.typography.bodySmall)
            Text("Nozzle can: " + CAPABILITY_WORDS.filterKeys { it in snap.capabilities }.values.joinToString(", "), style = MaterialTheme.typography.bodySmall)
            Text(snap.classified.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (snap.description.kind == TargetKind.PHYSICAL) Row(Modifier.fillMaxWidth().selectable(s.targetConfirmed, role = Role.Checkbox) { c.confirmTarget(!s.targetConfirmed) }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(s.targetConfirmed, null, modifier = Modifier.testTag("confirm-target"))
            Text("This is the printer in front of me, and I own it.")
        }
        Button({ c.toSuites() }, enabled = !s.busy && ok && (snap.description.kind == TargetKind.SIMULATED || s.targetConfirmed), modifier = Modifier.testTag("to-suites")) { Text("Continue") }
    }
}

@Composable private fun SuiteStep(c: TestModeController, s: TestModeState) {
    val context = LocalContext.current
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { u -> runCatching { context.contentResolver.openInputStream(u)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()?.let { c.importSuite(it) } }
    }
    val snap = s.snapshot
    // "For this printer": the verified printer type and firmware family both match. Everything else is for other printers.
    val (matching, others) = s.suites.partition { snap != null && it.target.firmwareFamily == snap.classified.family &&
        (it.target.printerKinds.isEmpty() || snap.description.printerKind.name in it.target.printerKinds) }
    var showOthers by remember { mutableStateOf(false) }
    Section("3. Choose a suite") {
        if (matching.isEmpty()) Text("No bundled suite matches this printer (${snap?.classified?.family}). You can import one a maintainer sent you.", style = MaterialTheme.typography.bodySmall)
        matching.forEach { suite ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(suite.title, style = MaterialTheme.typography.titleMedium)
                    Text(suiteSummary(suite), style = MaterialTheme.typography.bodySmall)
                    Text(if (suite.coverage == com.nozzleitall.testgrid.Coverage.REFERENCE) "Reference suite" else "Community suite: results stay unverified until accepted"
                        + if (suite.id in s.importedSuiteIds) " · imported" else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button({ c.selectSuite(suite) }, enabled = !s.busy, modifier = Modifier.testTag("suite-${suite.id}")) { Text("Use this suite") }
                }
            }
        }
        if (others.isNotEmpty()) TextButton({ showOthers = !showOthers }, modifier = Modifier.testTag("other-suites")) {
            Text(if (showOthers) "Hide suites for other printers" else "Show ${others.size} suites for other printers")
        }
        if (showOthers) others.forEach { suite ->
            Row(Modifier.fillMaxWidth().selectable(s.suite == suite, role = Role.Button) { c.selectSuite(suite) }.padding(vertical = 8.dp).testTag("suite-${suite.id}")) {
                Column(Modifier.weight(1f)) {
                    Text(suite.title, style = MaterialTheme.typography.bodyMedium)
                    Text("For ${suite.target.manufacturer} ${suite.target.model} on ${suite.target.firmwareFamily}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (s.mismatches.isNotEmpty()) Column(Modifier.testTag("suite-mismatch")) {
            Text("${s.suite?.title} can't run on this printer:", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            s.mismatches.forEach { Text("• $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton({ c.back() }) { Text("Back") }
            TextButton({ importer.launch(arrayOf("application/json", "text/plain", "*/*")) }, modifier = Modifier.testTag("import-suite")) { Text("Import a suite file") }
        }
    }
}

private fun suiteSummary(suite: com.nozzleitall.testgrid.Suite): String {
    val cats = com.nozzleitall.testgrid.Category.entries.filter { c -> suite.tests.any { it.category == c } }.joinToString(", ") { it.label.lowercase() }
    return "${suite.tests.size} tests · $cats"
}

/** What each level adds, in the words an operator decides by. */
private fun levelAdds(l: SafetyLevel): String = when (l) {
    SafetyLevel.SOFTWARE -> "slicing and G-code checks on this phone"
    SafetyLevel.READ_ONLY -> "reads status, camera and files; nothing changes"
    SafetyLevel.REVERSIBLE_FILES -> "uploads one test file, then deletes it"
    SafetyLevel.SUPERVISED_CONTROLS -> "low heat, homing and a small move, at the printer"
    SafetyLevel.PHYSICAL_PRINT -> "full prints, pause, resume and cancel"
}

@Composable private fun PlanStep(c: TestModeController, s: TestModeState) {
    val suite = s.suite ?: return
    val models = remember { runCatching { ModelLibrary.fromResources() }.getOrNull() }
    // Tests that already passed here stand unless unticked; one whose files a later test uses runs again anyway.
    val offered = s.carryAvailable.filterKeys { id -> suite.test(id)?.let { it.safetyLevel <= s.maxLevel } == true }
    val carried = com.nozzleitall.testgrid.CarryOver.effective(suite, s.maxLevel, offered.filterKeys { it in s.carryChosen })
    val running = suite.tests.filter { it.safetyLevel <= s.maxLevel && it.id !in carried }
    val skipped = suite.tests.filter { it.safetyLevel > s.maxLevel }
    Section("4. How far to go") {
        Text(suite.title, fontWeight = FontWeight.Bold)
        Column(Modifier.testTag("safety-levels")) {
            // Only levels that run something: on COSMOS even slicing reads the printer first, so level 0 would run nothing.
            SafetyLevel.entries.filter { it >= suite.minSafetyLevel && it <= suite.maxSafetyLevel }.forEach { l ->
                val n = suite.tests.count { it.safetyLevel <= l }
                Row(Modifier.fillMaxWidth().selectable(s.maxLevel == l, role = Role.RadioButton) { c.setMaxLevel(l) }.padding(vertical = 4.dp).testTag("level-${l.level}"), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(s.maxLevel == l, null)
                    Column(Modifier.weight(1f)) {
                        Text("Level ${l.level} · $n tests", style = MaterialTheme.typography.bodyMedium, fontWeight = if (s.maxLevel == l) FontWeight.Bold else FontWeight.Normal)
                        Text(levelAdds(l), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton({ c.back() }) { Text("Back") }
            Button({ c.start() }, enabled = !s.busy && running.isNotEmpty(), modifier = Modifier.testTag("start-run")) { Text("Start run · ${running.size} tests") }
        }
        Text("Every step that changes the printer waits for your approval. Tests above the chosen level are recorded as skipped, never as passed.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (offered.isNotEmpty()) Section("Already passed (${carried.size} of ${offered.size} not repeated)") {
        Text("These passed in an earlier run on this printer, firmware and Nozzle version. Untick one to run it again.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        suite.tests.filter { it.id in offered }.forEach { t ->
            val prior = offered.getValue(t.id)
            val chosen = t.id in s.carryChosen
            Row(Modifier.fillMaxWidth().selectable(chosen, role = Role.Checkbox) { c.setCarry(t.id, !chosen) }.padding(vertical = 4.dp).testTag("carry-${t.id}"),
                verticalAlignment = Alignment.CenterVertically) {
                Checkbox(chosen, null)
                Column(Modifier.weight(1f)) {
                    Text(t.title, style = MaterialTheme.typography.bodyMedium)
                    val date = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(prior.completedAt))
                    Text(when {
                        !chosen -> "Runs again"
                        t.id !in carried -> "Runs again: a later test in this run uses its files"
                        else -> "Passed $date · not repeated"
                    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    Section("Will run (${running.size})") { running.forEach { PlanTestRow(it, models, true) } }
    if (skipped.isNotEmpty()) Section("Skipped at this level (${skipped.size})") { skipped.forEach { PlanTestRow(it, models, false) } }
}

/** One test, collapsed to a line; tap to see every step it will take. */
@Composable private fun PlanTestRow(t: com.nozzleitall.testgrid.TestCase, models: ModelLibrary?, runs: Boolean) {
    var open by remember(t.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().selectable(open, role = Role.Button) { open = !open }.padding(vertical = 6.dp).testTag("plan-${t.id}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (open) "▾ " else "▸ ", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(t.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = if (runs) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Text("L${t.safetyLevel.level}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(t.category.label + (if (t.scope == com.nozzleitall.testgrid.MaterialScope.MULTI) " · multi-material" else "") + (if (t.mutatesPrinterState) " · changes the printer" else ""),
            style = MaterialTheme.typography.labelSmall, color = if (t.mutatesPrinterState) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 18.dp))
        if (open) Column(Modifier.padding(start = 18.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (t.preconditions.isNotEmpty()) Text("Before: " + t.preconditions.joinToString(" · ") { it.text }, style = MaterialTheme.typography.bodySmall)
            t.steps.forEachIndexed { i, st -> Text("${i + 1}. " + ManualInstructions.describe(st, models).replace("**", ""), style = MaterialTheme.typography.bodySmall,
                color = if (st.kind?.consequential == true) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface) }
            if (t.cleanup.isNotEmpty()) Text("Cleanup: " + t.cleanup.joinToString(" · ") { ManualInstructions.describe(it, models).replace("**", "") }, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun RunStep(c: TestModeController, s: TestModeState) {
    val session = c.session ?: return
    var showAll by remember { mutableStateOf(false) }
    val records = session.suite.tests.mapNotNull { t -> session.record.test(t.id)?.let { t to it } }
    val done = records.count { it.second.state.terminal }
    val current = records.firstOrNull { (_, r) -> session.record.tests.getOrNull(session.record.cursor)?.testId == r.testId && !r.state.terminal }
    Section("${session.suite.title}") {
        Text("$done of ${records.size} tests done" + (current?.let { " · now: ${it.first.title}" } ?: ""), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("run-progress"))
        LinearProgressIndicator({ if (records.isEmpty()) 0f else done.toFloat() / records.size }, Modifier.fillMaxWidth())
        val problems = records.filter { it.second.state.terminal && it.second.result in setOf(ResultState.FAIL, ResultState.BLOCKED, ResultState.UNVERIFIED) }
        problems.forEach { (t, r) -> Text("${r.result.name}: ${t.title}", style = MaterialTheme.typography.bodySmall, color = if (r.result == ResultState.FAIL) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
        TextButton({ showAll = !showAll }, modifier = Modifier.testTag("show-all-tests")) { Text(if (showAll) "Hide test list" else "Show all tests") }
        if (showAll) records.forEach { (t, r) ->
            val active = current?.second === r
            Row(Modifier.fillMaxWidth().testTag("result-${t.id}")) {
                Text(if (active) "▶ " else "  ", fontFamily = FontFamily.Monospace)
                Text(t.title, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Text(if (r.state.terminal) r.result.name else r.state.name.lowercase().replace('_', ' '), style = MaterialTheme.typography.labelSmall,
                    color = when (r.result) { ResultState.PASS -> MaterialTheme.colorScheme.primary; ResultState.FAIL -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.onSurfaceVariant }.takeIf { r.state.terminal } ?: MaterialTheme.colorScheme.onSurface)
            }
        }
    }
    when (val p = s.pending) {
        is Pending.Preconditions -> PreconditionsCard(c, p, s.busy)
        is Pending.Confirmation -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth().testTag("confirm-card")) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (p.phase == "cleanup") "Approve this cleanup step" else "Approve this step", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                Text(p.action, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.testTag("confirm-action"))
                Text("Printer: ${p.target}", color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.testTag("confirm-target-text"))
                Text("${p.test.title} · step ${p.step.id} · ${p.step.kind?.level?.label ?: ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                Text("Nozzle re-reads the printer first and sends nothing if its state has changed. This step is sent once; if the reply is lost it is never resent.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ c.approve(p.step.id) }, enabled = !s.busy, modifier = Modifier.testTag("approve-step")) { Text("Approve and send") }
                    OutlinedButton({ c.decline(p.step.id) }, enabled = !s.busy, modifier = Modifier.testTag("decline-step")) { Text("Decline") }
                }
            }
        }
        is Pending.Observation -> ObservationCard(c, p, s)
        is Pending.Attachment -> AttachmentCard(c, p, s.busy)
        is Pending.UnknownReview -> UnknownCard(c, p, s.busy)
        else -> if (!s.busy) Button({ c.proceed() }, modifier = Modifier.testTag("continue-run")) { Text("Continue") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton({ c.interrupt() }, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error), modifier = Modifier.testTag("interrupt-test")) { Text("Interrupt this test") }
        TextButton({ c.finishRun() }, enabled = !s.busy && s.pending !is Pending.UnknownReview, modifier = Modifier.testTag("end-run")) { Text("End run") }
    }
    Text("Interrupting stops further steps for this test and offers its cleanup. It does not stop a running print: use the printer's own controls or the dashboard's emergency stop for that.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable private fun PreconditionsCard(c: TestModeController, p: Pending.Preconditions, busy: Boolean) {
    val checks = remember(p.test.id) { mutableStateMapOf<String, Boolean>() }
    Section("Before \"${p.test.title}\"") {
        Text("${p.test.safetyLevel.label}. Confirm each only if it is true now.", style = MaterialTheme.typography.bodySmall)
        p.test.preconditions.forEach { pc ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checks[pc.id] == true, { checks[pc.id] = it }, modifier = Modifier.testTag("precondition-${pc.id}"))
                Text(pc.text + (pc.check?.let { " (also checked automatically)" } ?: ""))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ c.answerPreconditions(p.test.preconditions.associate { it.id to (checks[it.id] == true) }) }, enabled = !busy && p.test.preconditions.all { checks[it.id] == true }, modifier = Modifier.testTag("preconditions-met")) { Text("All true, continue") }
            OutlinedButton({ c.answerPreconditions(p.test.preconditions.associate { it.id to (checks[it.id] == true) }) }, enabled = !busy, modifier = Modifier.testTag("preconditions-unmet")) { Text("Not met: skip this test") }
        }
    }
}

/**
 * What Nozzle itself sees, beside a question that asks the operator to compare it with the printer. Found on the first
 * real run: "do Nozzle's temperatures match the printer?" is unanswerable if Test Mode doesn't show them.
 */
@Composable private fun ShownReading(c: TestModeController, p: Pending.Observation, s: TestModeState) {
    val show = p.step.params.optString("show").ifBlank { return }
    val shown = c.session?.record?.test(p.test.id)?.step(p.step.id)?.data?.optJSONObject("shown")
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth().testTag("observation-shown")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("What Nozzle reads now", style = MaterialTheme.typography.labelLarge)
            fun t(v: Any?) = (v as? Number)?.let { "%.1f".format(java.util.Locale.ROOT, it.toDouble()) } ?: "—"
            when {
                shown?.has("error") == true -> Text("Couldn't read the printer: ${shown.optString("error")}", color = MaterialTheme.colorScheme.error)
                show == "status" && shown != null -> {
                    Text("Nozzle ${t(shown.opt("nozzle"))} °C (target ${t(shown.opt("nozzleTarget"))} °C)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("shown-nozzle"))
                    Text("Bed ${t(shown.opt("bed"))} °C (target ${t(shown.opt("bedTarget"))} °C)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("shown-bed"))
                    Text("Printer state: ${shown.optString("state")}${if (shown.optBoolean("ready")) "" else " (not ready)"}", style = MaterialTheme.typography.bodySmall)
                }
                show == "slots" && shown != null -> (0 until (shown.optJSONArray("slots")?.length() ?: 0)).forEach { Text(shown.getJSONArray("slots").getString(it)) }
                show == "camera" -> CameraFrame(c, s.revision)
            }
            if (show != "camera") OutlinedButton({ c.refreshShown(p.step.id) }, enabled = !s.busy, modifier = Modifier.testTag("refresh-shown")) { Text("Refresh") }
        }
    }
}

@Composable private fun CameraFrame(c: TestModeController, revision: Int) {
    var frame by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var tries by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    androidx.compose.runtime.LaunchedEffect(tries, revision) {
        loading = true
        frame = c.cameraFrame()?.let { b -> runCatching { android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size) }.getOrNull() }
        loading = false
    }
    frame?.let { androidx.compose.foundation.Image(it.asImageBitmap(), "Camera", Modifier.fillMaxWidth().testTag("shown-camera")) }
        ?: Text(if (loading) "Loading the camera…" else "No picture from the camera.", style = MaterialTheme.typography.bodySmall)
    Text("Shown here only; camera pictures are never saved or exported.", style = MaterialTheme.typography.bodySmall)
    OutlinedButton({ tries++ }, modifier = Modifier.testTag("refresh-shown")) { Text("Refresh") }
}

@Composable private fun ObservationCard(c: TestModeController, p: Pending.Observation, s: TestModeState) {
    val busy = s.busy
    var value by remember(p.step.id) { mutableStateOf("") }
    var note by remember(p.step.id) { mutableStateOf("") }
    val params = p.step.params
    Section("Your observation") {
        Text(params.optString("question"), fontWeight = FontWeight.Bold, modifier = Modifier.testTag("observation-question"))
        ShownReading(c, p, s)
        when (params.optString("response")) {
            "yes_no" -> Column { listOf("yes", "no").forEach { v -> ChoiceRow(value == v, v.replaceFirstChar { it.uppercase() }, null, "answer-$v") { value = v } } }
            "pass_partial_fail" -> Column { listOf("pass" to "Pass", "partial" to "Partly", "fail" to "Fail").forEach { (v, l) -> ChoiceRow(value == v, l, null, "answer-$v") { value = v } } }
            "choice" -> Column { (0 until (params.optJSONArray("choices")?.length() ?: 0)).map { params.getJSONArray("choices").getString(it) }.forEach { v -> ChoiceRow(value == v, v.replaceFirstChar { it.uppercase() }, null, "answer-$v") { value = v } } }
            "number" -> OutlinedTextField(value, { value = it }, label = { Text("Measured value" + (params.optString("unit").takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")) },
                placeholder = { Text("with decimals, e.g. 10.58") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.testTag("answer-number"))
            else -> OutlinedTextField(value, { value = it }, label = { Text("What you saw") }, modifier = Modifier.fillMaxWidth().testTag("answer-text"))
        }
        OutlinedTextField(note, { note = it }, label = { Text("Note (optional; exported after redaction)") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ c.observe(p.step.id, value, note) }, enabled = !busy && value.isNotBlank(), modifier = Modifier.testTag("submit-observation")) { Text("Record") }
            OutlinedButton({ c.decline(p.step.id) }, enabled = !busy) { Text("Can't observe this") }
        }
    }
}

@Composable private fun AttachmentCard(c: TestModeController, p: Pending.Attachment, busy: Boolean) {
    val context = LocalContext.current
    var problem by remember(p.step.id) { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
            val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            if (bytes == null) problem = "That file couldn't be read." else c.attach(p.step.id, bytes, mime)
        }
    }
    Section("Attach evidence") {
        Text(p.step.params.optString("prompt").ifBlank { p.evidence?.description ?: "Attach the required file" }, fontWeight = FontWeight.Bold)
        Text("Photo location and camera metadata are removed before anything is stored. JPEG, PNG, plain text or JSON, up to 15 MB.", style = MaterialTheme.typography.bodySmall)
        problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ picker.launch(arrayOf("image/jpeg", "image/png", "text/plain", "application/json")) }, enabled = !busy, modifier = Modifier.testTag("pick-attachment")) { Text("Choose file") }
            OutlinedButton({ c.decline(p.step.id) }, enabled = !busy) { Text("I can't provide this") }
        }
    }
}

@Composable private fun UnknownCard(c: TestModeController, p: Pending.UnknownReview, busy: Boolean) {
    var note by remember { mutableStateOf("") }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth().testTag("unknown-card")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Outcome unknown: check the printer", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            Text("Nozzle can't tell whether the printer received: ${p.marker.action}", color = MaterialTheme.colorScheme.onErrorContainer)
            Text("It will not be sent again. Look at the printer now, describe what you find, and Nozzle will read the printer's current state before anything else can run.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            OutlinedTextField(note, { note = it }, label = { Text("What the printer is doing now") }, modifier = Modifier.fillMaxWidth().testTag("unknown-note"))
            Button({ c.reviewUnknown(note) }, enabled = !busy && note.isNotBlank(), modifier = Modifier.testTag("review-unknown")) { Text("Read the printer and record") }
        }
    }
}

@Composable private fun EvidenceStep(c: TestModeController, s: TestModeState, close: () -> Unit) {
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let { c.exportTo(it) } }
    val session = c.session
    val bundle = s.bundle
    Section("5. Review the evidence before export") {
        session?.suite?.tests?.forEach { t -> session.record.test(t.id)?.let { r -> Text("${t.title}: ${r.result}${if (r.reason.isNotBlank()) " - ${r.reason}" else ""}", style = MaterialTheme.typography.bodySmall) } }
        if (session?.record?.targetKind == TargetKind.SIMULATED) Text("Simulated run: every grade is UNVERIFIED. This bundle shows the workflow, not a printer.", color = MaterialTheme.colorScheme.tertiary)
        if (bundle == null) { if (!s.busy) Button({ c.proceed() }) { Text("Build the evidence bundle") }; return@Section }
        Text("This is exactly what will be exported. Credentials, addresses, hostnames, camera URLs, file paths and photo metadata have been removed; read it anyway.", style = MaterialTheme.typography.bodySmall)
        Text("Bundle digest ${bundle.bundleDigest}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("bundle-digest"))
        Text("Content hashes only; the bundle is not signed.", style = MaterialTheme.typography.bodySmall)
    }
    bundle?.preview()?.forEach { e ->
        var open by remember(e.path) { mutableStateOf(e.path == "evidence.json") }
        Card(Modifier.fillMaxWidth().testTag("preview-${e.path}")) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${e.path} · ${e.bytes} bytes", Modifier.weight(1f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                    if (e.text != null) TextButton({ open = !open }) { Text(if (open) "Hide" else "Show") }
                }
                Text("sha256 ${e.sha256}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                if (open && e.text != null) Text(e.text!!.take(60_000), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    if (bundle != null) Section("6. Send it to Nozzle It All") {
        val context = androidx.compose.ui.platform.LocalContext.current
        var code by remember { mutableStateOf(c.testerCode) }
        Text("Sends this bundle, exactly as shown above, to the Nozzle It All maintainers. Nothing else from your phone is sent.",
            style = MaterialTheme.typography.bodySmall)
        if (s.submitted == null) {
            OutlinedTextField(code, { code = it }, label = { Text("Your tester code") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("tester-code"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ c.submit(code) }, enabled = !s.busy && code.isNotBlank(), modifier = Modifier.testTag("submit-bundle")) { Text("Send to Nozzle It All") }
                OutlinedButton({ c.emailBundle(context) }, enabled = !s.busy, modifier = Modifier.testTag("email-bundle")) { Text("Email it instead") }
            }
        }
        s.submitted?.let { Text("Received by Nozzle It All: $it. Thank you.", color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("submitted")) }
        s.submitError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("submit-error")) }
    }
    if (bundle != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton({ exporter.launch("nozzle-evidence-${session?.suite?.id}-${bundle.bundleDigest.take(12)}.zip") }, enabled = !s.busy, modifier = Modifier.testTag("export-bundle")) { Text("Save a copy") }
        OutlinedButton({ c.clearRun(); close() }, enabled = !s.busy, modifier = Modifier.testTag("clear-run")) { Text(if (s.exported != null || s.submitted != null) "Done: remove from this device" else "Discard without sending") }
    }
    s.exported?.let { Text("Saved bundle $it", color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("exported")) }
}
