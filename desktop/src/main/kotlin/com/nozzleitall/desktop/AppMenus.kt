package com.nozzleitall.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.rememberDialogState
import com.nozzleitall.desktop.prepare.SliceState
import com.nozzleitall.desktop.prepare.chooseFiles
import com.nozzleitall.desktop.prepare.chooseSaveFile
import com.nozzleitall.desktop.settings.SettingsCatalog
import com.nozzleitall.desktop.ui.*
import com.nozzleitall.project.ThreeMf
import com.nozzleitall.printer.Glossary

private fun browse(url: String) { runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) } }

/**
 * The application menu bar. Every item does something that also exists on screen; the menus are for discoverability
 * and the keyboard. Actions that would lose unsaved work ask first.
 */
@Composable
fun FrameWindowScope.AppMenuBar(state: AppState, onQuit: () -> Unit) {
    val p = state.prepare
    fun guarded(action: () -> Unit) { if (p.dirty && p.items.isNotEmpty()) state.pendingDiscard = action else action() }
    fun report(e: Throwable) { p.notice = e.message ?: "That didn't work." }
    MenuBar {
        Menu("File", mnemonic = 'F') {
            Item("New project", shortcut = KeyShortcut(Key.N, ctrl = true)) { guarded { p.newProject(); state.destination = Destination.PREPARE } }
            Item("Open project…", shortcut = KeyShortcut(Key.O, ctrl = true)) {
                guarded { chooseFiles("Open a project", listOf("3mf"), multiple = false).firstOrNull()?.let { f -> runCatching { p.open(f); state.destination = Destination.PREPARE }.onFailure(::report) } }
            }
            Item("Add model…", shortcut = KeyShortcut(Key.I, ctrl = true)) {
                chooseFiles("Add a model", listOf("stl", "3mf", "obj")).forEach { f -> runCatching { p.importModel(f) }.onFailure(::report) }
                state.destination = Destination.PREPARE
            }
            Separator()
            Item("Save", shortcut = KeyShortcut(Key.S, ctrl = true), enabled = p.items.isNotEmpty()) { runCatching { p.save() }.onFailure(::report) }
            Item("Save as…", shortcut = KeyShortcut(Key.S, ctrl = true, shift = true), enabled = p.items.isNotEmpty()) {
                chooseSaveFile("Save project as", p.name + ".3mf")?.let { f -> runCatching { p.file = f; p.save() }.onFailure(::report) }
            }
            Item("Export 3MF…", enabled = p.items.isNotEmpty()) {
                chooseSaveFile("Export project", p.name + ".3mf")?.let { f -> runCatching { ThreeMf.writeAtomically(p.toProject(), f) }.onFailure(::report) }
            }
            Item("Export sliced file…", shortcut = KeyShortcut(Key.E, ctrl = true), enabled = p.slice is SliceState.Done) {
                (p.slice as? SliceState.Done)?.let { s -> chooseSaveFile("Export sliced file", p.name + ".gcode")?.let { t -> runCatching { s.result.gcode.copyTo(t, overwrite = true) }.onFailure(::report) } }
            }
            Separator()
            Item("Quit", shortcut = KeyShortcut(Key.Q, ctrl = true)) { onQuit() }
        }
        Menu("Edit", mnemonic = 'E') {
            Item("Duplicate", shortcut = KeyShortcut(Key.D, ctrl = true), enabled = p.selected != null) { p.duplicateSelected() }
            Item("Delete", shortcut = KeyShortcut(Key.Delete), enabled = p.selected != null) { p.removeSelected() }
            Item("Arrange plate", shortcut = KeyShortcut(Key.A, ctrl = true, shift = true), enabled = p.items.isNotEmpty()) { if (!p.arrange()) p.notice = "Not everything fits on the plate." }
            Separator()
            Item("All settings…", shortcut = KeyShortcut(Key.Period, ctrl = true)) { p.showAllSettings = true; state.destination = Destination.PREPARE }
        }
        Menu("Go", mnemonic = 'G') {
            Destination.entries.forEach { d -> Item(d.label, shortcut = KeyShortcut(d.key, ctrl = true)) { state.destination = d } }
        }
        Menu("Help", mnemonic = 'H') {
            Item("Nozzle It All help") { browse("https://nozzleitall.com/docs/") }
            Item("Supported printers") { browse("https://nozzleitall.com/printers/") }
            Item("Report a problem") { browse("https://nozzleitall.com/support/") }
            Item("Source code and licences") { browse("https://nozzleitall.com/open-source/") }
            Separator()
            Item("About Nozzle It All") { state.showAbout = true }
        }
    }
}

/** About: this app's own version and what it's built on, never another product's version. */
@Composable
fun AboutWindow(state: AppState, onClose: () -> Unit) {
    DialogWindow(onCloseRequest = onClose, title = "About ${Glossary.PRODUCT_FAMILY}", state = rememberDialogState(width = 520.dp, height = 600.dp), resizable = false,
        onPreviewKeyEvent = { if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) { onClose(); true } else false }) {
        val c = Nz.colors
        val engine = com.nozzleitall.desktop.prepare.SliceEngine.locateEngine()
        Column(Modifier.fillMaxSize().background(c.surface).padding(28.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.Start) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                androidx.compose.foundation.Image(painterResource("brand/mark-violet.svg"), null, Modifier.size(64.dp))
                Column {
                    Txt(Glossary.PRODUCT_FAMILY, Nz.type.headline)
                    Txt("Version ${state.version}", Nz.type.body, c.textMuted)
                }
            }
            Txt("Prepare, slice, send and watch prints on the printers you own. Everything runs on this computer and your own network.", Nz.type.body)
            if (engine == null) Txt("The slicing engine isn't installed with this copy. Reinstall the package.", Nz.type.bodySmall, c.danger)
            SectionHeader("Credits and licence")
            Txt("Nozzle It All is free software under the GNU Affero General Public License, version 3 or later. Its complete source code is available from nozzleitall.com.", Nz.type.bodySmall, c.textMuted)
            Txt("Its slicing engine is derived from OrcaSlicer${SettingsCatalog.bundled.engineCommit?.let { " (source ${it.take(8)})" } ?: ""} by SoftFever and contributors, based on Bambu Studio by Bambu Lab, " +
                "which is based on PrusaSlicer by Prusa Research, from Slic3r by Alessandro Ranellucci and the RepRap community.",
                Nz.type.bodySmall, c.textMuted)
            Txt("Nozzle It All is not made or endorsed by Snapmaker, Bambu Lab, Prusa Research or any other printer maker.", Nz.type.bodySmall, c.textMuted)
            Txt("© 2026 Nozzle It All contributors.", Nz.type.bodySmall, c.textMuted)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NzButton("nozzleitall.com", { browse("https://nozzleitall.com") }, kind = ButtonKind.QUIET)
                NzButton("Source and licences", { browse("https://nozzleitall.com/open-source/") }, kind = ButtonKind.QUIET)
                Spacer(Modifier.weight(1f))
                NzButton("Close", onClose, kind = ButtonKind.PRIMARY)
            }
        }
    }
}
