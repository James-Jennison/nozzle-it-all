package net.jamesjennison.klippercompanion.testgrid

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun TargetStep(c: TestModeController, s: TestModeState) {
    if (s.resumable) Section("An unfinished run is saved") {
        Text("Resuming never repeats a command: one that was in flight when Nozzle stopped is recorded as an unknown outcome for you to check.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ c.resume() }, modifier = Modifier.testTag("resume-run")) { Text("Resume run") }
            OutlinedButton({ c.discardSaved() }, modifier = Modifier.testTag("discard-run")) { Text("Discard it") }
        }
    }
    Section("1. Choose the printer to test") {
        c.targets.forEachIndexed { i, t ->
            val selected = s.target == t
            FilterChip(selected, { c.selectTarget(t) }, label = { Text(t.title) }, modifier = Modifier.testTag("target-$i"))
        }
        Text("Simulated printers exercise Test Mode without hardware. Their results are marked simulated and never count as evidence for any printer.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (TestModeController.needsDeclaration(s.target)) Section("Which firmware does this Centauri Carbon run?") {
        Text("Elegoo's LAN protocol doesn't say whether the firmware is stock or OpenCentauri-patched. They are graded separately.", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TestModeController.ELEGOO_DECLARABLE.forEach { f -> FilterChip(s.declaredFamily == f, { s.target?.let { c.selectTarget(it, f) } }, label = { Text(f) }) }
        }
    }
    val snap = s.snapshot ?: return
    Section("2. Verify it") {
        Row { Text("Model ", fontWeight = FontWeight.Bold); Text("${snap.description.manufacturer} ${snap.description.model}") }
        Row { Text("Firmware ", fontWeight = FontWeight.Bold); Text("${snap.classified.family} · ${snap.identity?.firmware?.let { "${it.app.ifBlank { "(no app name)" }} ${it.version}" } ?: "not reported"}", modifier = Modifier.testTag("target-firmware")) }
        Text(snap.classified.detail, style = MaterialTheme.typography.bodySmall)
        Row { Text("Adapter ", fontWeight = FontWeight.Bold); Text("${snap.description.adapter} (${snap.description.protocol})") }
        Row { Text("Status ", fontWeight = FontWeight.Bold); Text(snap.status?.let { "${it.state}${if (it.ready) "" else " (not ready)"} · nozzle ${it.nozzle} °C · bed ${it.bed} °C" } ?: "unreadable") }
        Text("Capabilities", fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { snap.capabilities.sorted().forEach { AssistChip({}, label = { Text(it) }) } }
        snap.classified.hardware.filterValues { it }.keys.takeIf { it.isNotEmpty() }?.let { Text("Detected hardware: ${it.joinToString()}") }
        snap.problems.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
        if (snap.description.kind == TargetKind.PHYSICAL) Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(s.targetConfirmed, { c.confirmTarget(it) }, modifier = Modifier.testTag("confirm-target"))
            Text("This is the printer in front of me, and I own it.")
        }
        Button({ c.toSuites() }, enabled = !s.busy && snap.problems.isEmpty() && (snap.description.kind == TargetKind.SIMULATED || s.targetConfirmed), modifier = Modifier.testTag("to-suites")) { Text("Choose a test suite") }
    }
}

@Composable private fun SuiteStep(c: TestModeController, s: TestModeState) {
    val context = LocalContext.current
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { u -> runCatching { context.contentResolver.openInputStream(u)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()?.let { c.importSuite(it) } }
    }
    val kind = s.snapshot?.description?.printerKind
    Section("3. Choose a suite") {
        val (matching, others) = s.suites.partition { kind != null && (it.target.printerKinds.isEmpty() || kind.name in it.target.printerKinds) }
        (matching + others).forEachIndexed { i, suite ->
            Card(colors = CardDefaults.cardColors(containerColor = if (s.suite == suite) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(suite.title, fontWeight = FontWeight.Bold)
                    Text("${suite.id} ${suite.version} · ${suite.coverage.id}${if (suite.id in s.importedSuiteIds) " · imported" else ""} · up to ${suite.maxSafetyLevel.label}", style = MaterialTheme.typography.bodySmall)
                    TextButton({ c.selectSuite(suite) }, modifier = Modifier.testTag("suite-${suite.id}")) { Text(if (suite in matching) "Use this suite" else "Check against this printer") }
                }
            }
            if (i == matching.size - 1 && others.isNotEmpty()) HorizontalDivider()
        }
        OutlinedButton({ importer.launch(arrayOf("application/json", "text/plain", "*/*")) }, modifier = Modifier.testTag("import-suite")) { Text("Import a suite (JSON)") }
        if (s.mismatches.isNotEmpty()) Column(Modifier.testTag("suite-mismatch")) {
            Text("This suite can't run on this printer:", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            s.mismatches.forEach { Text("• $it", color = MaterialTheme.colorScheme.error) }
        }
        TextButton({ c.back() }) { Text("Back") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun PlanStep(c: TestModeController, s: TestModeState) {
    val suite = s.suite ?: return
    val models = remember { runCatching { ModelLibrary.fromResources() }.getOrNull() }
    Section("4. Review the plan") {
        Text(suite.title, fontWeight = FontWeight.Bold)
        Text(suite.description, style = MaterialTheme.typography.bodySmall)
        Text("Highest safety level to allow in this run", fontWeight = FontWeight.Bold)
        Column(Modifier.testTag("safety-levels")) {
            SafetyLevel.entries.filter { it <= suite.maxSafetyLevel }.forEach { l ->
                Row(Modifier.fillMaxWidth().selectable(s.maxLevel == l, role = Role.RadioButton) { c.setMaxLevel(l) }.testTag("level-${l.level}"), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(s.maxLevel == l, null); Text(l.label)
                }
            }
        }
        Text("Tests above this level are recorded as skipped, never as passed.", style = MaterialTheme.typography.bodySmall)
    }
    suite.tests.forEach { t ->
        val inRun = t.safetyLevel <= s.maxLevel
        Section("${t.title}${if (inRun) "" else " (skipped at this level)"}") {
            Text("${t.category.label} · ${t.scope.label} · ${t.safetyLevel.label}${if (t.mutatesPrinterState) " · changes the printer" else ""}", style = MaterialTheme.typography.bodySmall)
            if (t.preconditions.isNotEmpty()) Text("Before: " + t.preconditions.joinToString(" · ") { it.text }, style = MaterialTheme.typography.bodySmall)
            t.steps.forEachIndexed { i, st -> Text("${i + 1}. " + ManualInstructions.describe(st, models).replace("**", ""), style = MaterialTheme.typography.bodySmall,
                color = if (st.kind?.consequential == true) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface) }
            if (t.cleanup.isNotEmpty()) Text("Cleanup: " + t.cleanup.joinToString(" · ") { ManualInstructions.describe(it, models).replace("**", "") }, style = MaterialTheme.typography.bodySmall)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton({ c.back() }) { Text("Back") }
        Button({ c.start() }, enabled = !s.busy, modifier = Modifier.testTag("start-run")) { Text("Start run") }
    }
}

@Composable private fun RunStep(c: TestModeController, s: TestModeState) {
    val session = c.session ?: return
    Section("Run ${session.record.runId.take(8)} · ${session.suite.id}") {
        session.suite.tests.forEach { t ->
            val r = session.record.test(t.id) ?: return@forEach
            val active = session.record.tests.getOrNull(session.record.cursor)?.testId == t.id && !r.state.terminal
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
        is Pending.Observation -> ObservationCard(c, p, s.busy)
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

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun ObservationCard(c: TestModeController, p: Pending.Observation, busy: Boolean) {
    var value by remember(p.step.id) { mutableStateOf("") }
    var note by remember(p.step.id) { mutableStateOf("") }
    val params = p.step.params
    Section("Your observation") {
        Text(params.optString("question"), fontWeight = FontWeight.Bold, modifier = Modifier.testTag("observation-question"))
        when (params.optString("response")) {
            "yes_no" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("yes", "no").forEach { v -> FilterChip(value == v, { value = v }, label = { Text(v) }, modifier = Modifier.testTag("answer-$v")) } }
            "pass_partial_fail" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("pass", "partial", "fail").forEach { v -> FilterChip(value == v, { value = v }, label = { Text(v) }, modifier = Modifier.testTag("answer-$v")) } }
            "choice" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { (0 until (params.optJSONArray("choices")?.length() ?: 0)).map { params.getJSONArray("choices").getString(it) }.forEach { v -> FilterChip(value == v, { value = v }, label = { Text(v) }) } }
            "number" -> OutlinedTextField(value, { value = it }, label = { Text("Measured value" + (params.optString("unit").takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")) },
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
    if (bundle != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button({ exporter.launch("nozzle-evidence-${session?.suite?.id}-${bundle.bundleDigest.take(12)}.zip") }, enabled = !s.busy, modifier = Modifier.testTag("export-bundle")) { Text("Export bundle") }
        OutlinedButton({ c.clearRun(); close() }, enabled = !s.busy, modifier = Modifier.testTag("clear-run")) { Text(if (s.exported != null) "Done: remove from this device" else "Discard without exporting") }
    }
    s.exported?.let { Text("Exported bundle $it", color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("exported")) }
}
