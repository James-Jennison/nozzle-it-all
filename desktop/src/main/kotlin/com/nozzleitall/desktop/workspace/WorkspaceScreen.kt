package com.nozzleitall.desktop.workspace

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nozzleitall.desktop.AppState
import com.nozzleitall.desktop.Destination
import com.nozzleitall.desktop.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * The deliberate door into the Advanced Workspace: say what it's for, open the current project there, and bring the
 * result back safely. Everyday work never needs it.
 */
@Composable
fun WorkspaceScreen(state: AppState) {
    val c = Nz.colors
    val ws = state.workspace
    val p = state.prepare
    var session by remember { mutableStateOf<AdvancedWorkspace.Session?>(null) }
    var running by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<String, BannerKind>?>(null) }
    var conflict by remember { mutableStateOf<AdvancedWorkspace.ReturnResult.Conflict?>(null) }
    val binary = remember { AdvancedWorkspace.locate() }

    suspend fun bringBack(s: AdvancedWorkspace.Session) {
        val result = withContext(Dispatchers.IO) { ws.collect(s, p.manifest?.revision ?: s.baseRevision, p.toProject()) }
        when (result) {
            AdvancedWorkspace.ReturnResult.Unchanged -> { message = "No changes were saved in the Advanced Workspace." to BannerKind.INFO; ws.finish(s, "closed-unchanged") }
            is AdvancedWorkspace.ReturnResult.Refused -> { message = result.message to BannerKind.DANGER; ws.finish(s, "refused") }
            is AdvancedWorkspace.ReturnResult.Conflict -> conflict = result
            is AdvancedWorkspace.ReturnResult.Changed -> {
                withContext(Dispatchers.IO) { state.library.save(result.project, s.originalFile) }
                p.open(s.originalFile)
                message = (if (result.restoredManifest) "Changes brought back. The workspace dropped Nozzle's material choices, so they were restored; check them in Prepare." else "Changes from the Advanced Workspace are now in your project.") to BannerKind.SUCCESS
                ws.finish(s, "returned")
            }
        }
        session = null
    }

    LaunchedEffect(session, running) {
        val s = session ?: return@LaunchedEffect
        while (isActive && running) {
            if (ws.running?.isAlive != true) { running = false; bringBack(s) }
            delay(1000)
        }
    }

    Column(Modifier.fillMaxSize().padding(28.dp).verticalScroll(rememberScrollState()).widthIn(max = 960.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        SectionHeader("Advanced Workspace", "Specialist tools for when Prepare isn't enough.")
        message?.let { (t, k) -> Banner(t, k, "Dismiss" to { message = null }) }
        Card(Modifier.fillMaxWidth()) {
            Txt("What it's for", Nz.type.title)
            Txt("Painting supports and seams, modifier shapes, variable layer height, Full Spectrum mixing ratios and every engine setting. It opens your project in a separate window " +
                "and keeps its settings apart from any other slicer on this computer.", Nz.type.body, c.textMuted)
            Txt("Your project comes back to Nozzle when you close that window. Nozzle checks the saved file first and never replaces your project with a damaged or incomplete one.",
                Nz.type.bodySmall, c.textMuted)
        }
        if (binary == null) Banner("The Advanced Workspace isn't installed with this copy of Nozzle It All.", BannerKind.WARNING)
        val s = session
        if (s == null) {
            if (p.items.isEmpty()) Card(Modifier.fillMaxWidth()) {
                EmptyState(NzIcon.WORKSPACE, "Open a project first", "Prepare or open a project, then bring it here.") {
                    NzButton("Go to Projects", { state.destination = Destination.PROJECTS }, kind = ButtonKind.PRIMARY)
                }
            } else Card(Modifier.fillMaxWidth()) {
                Txt("Current project: ${p.name}", Nz.type.title)
                NzButton("Open in Advanced Workspace", {
                    runCatching {
                        val file = p.save()
                        val sess = ws.begin(file)
                        ws.launch(sess, binary ?: error("The Advanced Workspace isn't installed."))
                        session = sess; running = true; message = null
                    }.onFailure { message = (it.message ?: "The Advanced Workspace couldn't start.") to BannerKind.DANGER }
                }, kind = ButtonKind.PRIMARY, icon = NzIcon.WORKSPACE, enabled = binary != null, testTag = "open-workspace")
            }
        } else Card(Modifier.fillMaxWidth()) {
            Txt("Open in the Advanced Workspace", Nz.type.title)
            Txt("Save your changes there, then close its window to bring them back. Nozzle keeps your original until then.", Nz.type.body, c.textMuted)
        }
        conflict?.let { cf ->
            Card(Modifier.fillMaxWidth(), raised = true) {
                Txt("Changed in two places", Nz.type.title)
                Txt(cf.message, Nz.type.body)
                androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NzButton("Keep both", {
                        val copy = state.library.fileFor(p.name + " (Advanced Workspace)")
                        val m = cf.workspaceVersion.manifest!!
                        state.library.save(cf.workspaceVersion.copy(manifest = m.copy(projectId = java.util.UUID.randomUUID().toString(), name = m.name + " (Advanced Workspace)")), copy)
                        message = "Saved the Advanced Workspace version as a separate project: ${copy.nameWithoutExtension}." to BannerKind.SUCCESS; conflict = null
                    }, kind = ButtonKind.PRIMARY)
                    NzButton("Use the Advanced Workspace version", {
                        p.file?.let { state.library.save(cf.workspaceVersion, it); p.open(it) }; conflict = null
                        message = "Your project now has the Advanced Workspace version." to BannerKind.SUCCESS
                    }, kind = ButtonKind.SECONDARY)
                    NzButton("Keep Nozzle's version", { conflict = null; message = "Kept your Nozzle version." to BannerKind.INFO }, kind = ButtonKind.QUIET)
                }
            }
        }
    }
}
