package com.nozzleitall.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.nozzleitall.desktop.prepare.SliceState
import com.nozzleitall.desktop.prepare.chooseFiles
import com.nozzleitall.desktop.prepare.chooseSaveFile
import com.nozzleitall.desktop.settings.SettingsCatalog
import com.nozzleitall.desktop.ui.*
import com.nozzleitall.project.ThreeMf
import com.nozzleitall.printer.Glossary

private fun browse(url: String) { runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) } }

/** One menu item: what it says, its shortcut, whether it can run now, and what it does. */
class MenuAction(val label: String, val shortcut: Shortcut? = null, val enabled: Boolean = true, val run: () -> Unit)
class Shortcut(val key: Key, val ctrl: Boolean = false, val shift: Boolean = false, val text: String) {
    fun matches(e: androidx.compose.ui.input.key.KeyEvent) = e.key == key && e.isCtrlPressed == ctrl && e.isShiftPressed == shift && !e.isAltPressed
}
/** A menu: title, the Alt+letter that opens it, and its items (null is a separator). */
class AppMenu(val title: String, val mnemonic: Key, val items: List<MenuAction?>)

private fun ctrl(key: Key, text: String, shift: Boolean = false) = Shortcut(key, ctrl = true, shift = shift, text = (if (shift) "Ctrl+Shift+" else "Ctrl+") + text)

/**
 * The application's menus, as data: drawn by [NozzleMenuBar] in Nozzle's own style, and their shortcuts handled by
 * [handleMenuShortcut]. Every item does something that also exists on screen. Actions that would lose work ask first.
 */
fun appMenus(state: AppState, onQuit: () -> Unit): List<AppMenu> {
    val p = state.prepare
    fun guarded(action: () -> Unit) { if (p.dirty && p.items.isNotEmpty()) state.pendingDiscard = action else action() }
    fun report(e: Throwable) { p.notice = e.message ?: "That didn't work." }
    val sliced = p.slice as? SliceState.Done
    return listOf(
        AppMenu("File", Key.F, listOf(
            MenuAction("New project", ctrl(Key.N, "N")) { guarded { p.newProject(); state.destination = Destination.PREPARE } },
            MenuAction("Open project…", ctrl(Key.O, "O")) {
                guarded { chooseFiles("Open a project", listOf("3mf"), multiple = false).firstOrNull()?.let { f -> runCatching { p.open(f); state.destination = Destination.PREPARE }.onFailure(::report) } }
            },
            MenuAction("Add model…", ctrl(Key.I, "I")) {
                chooseFiles("Add a model", listOf("stl", "3mf", "obj")).forEach { f -> runCatching { p.importModel(f) }.onFailure(::report) }
                state.destination = Destination.PREPARE
            },
            null,
            MenuAction("Save", ctrl(Key.S, "S"), enabled = p.items.isNotEmpty()) { runCatching { p.save() }.onFailure(::report) },
            MenuAction("Save as…", ctrl(Key.S, "S", shift = true), enabled = p.items.isNotEmpty()) {
                chooseSaveFile("Save project as", p.name + ".3mf")?.let { f -> runCatching { p.file = f; p.save() }.onFailure(::report) }
            },
            MenuAction("Export 3MF…", enabled = p.items.isNotEmpty()) {
                chooseSaveFile("Export project", p.name + ".3mf")?.let { f -> runCatching { ThreeMf.writeAtomically(p.toProject(), f) }.onFailure(::report) }
            },
            MenuAction("Export sliced file…", ctrl(Key.E, "E"), enabled = sliced != null) {
                sliced?.let { s -> chooseSaveFile("Export sliced file", p.name + ".gcode")?.let { t -> runCatching { s.result.gcode.copyTo(t, overwrite = true) }.onFailure(::report) } }
            },
            null,
            MenuAction("Quit", ctrl(Key.Q, "Q")) { onQuit() },
        )),
        AppMenu("Edit", Key.E, listOf(
            MenuAction("Duplicate", ctrl(Key.D, "D"), enabled = p.selected != null) { p.duplicateSelected() },
            MenuAction("Delete", Shortcut(Key.Delete, text = "Delete"), enabled = p.selected != null) { p.removeSelected() },
            MenuAction("Arrange plate", ctrl(Key.A, "A", shift = true), enabled = p.items.isNotEmpty()) { p.arrange() },
            null,
            MenuAction("Show every setting", ctrl(Key.Period, ".")) { p.advancedSettings = true; p.settingsScope = com.nozzleitall.desktop.settings.Scope.PROCESS; state.destination = Destination.PREPARE },
        )),
        AppMenu("Go", Key.G, Destination.entries.map { d -> MenuAction(d.label, Shortcut(d.key, ctrl = true, text = "Ctrl+" + if (d.key == Key.Comma) "," else d.key.toString().removePrefix("Key: "))) { state.destination = d } }),
        AppMenu("Help", Key.H, listOf(
            MenuAction("Nozzle It All help") { browse("https://nozzleitall.com/docs/") },
            MenuAction("Supported printers") { browse("https://nozzleitall.com/printers/") },
            MenuAction("Report a problem") { browse("https://nozzleitall.com/support/") },
            MenuAction("Source code and licences") { browse("https://nozzleitall.com/open-source/") },
            null,
            MenuAction("About Nozzle It All") { state.showAbout = true },
        )),
    )
}

/**
 * Runs the enabled menu item whose shortcut [e] is; true if one matched. [plain] selects which shortcuts to try: Ctrl
 * shortcuts are checked before anything else sees the key (typing never uses Ctrl), plain keys like Delete only after
 * the focused control has had its chance, so Delete in a text field edits the text instead of deleting an object.
 */
fun handleMenuShortcut(menus: List<AppMenu>, e: androidx.compose.ui.input.key.KeyEvent, plain: Boolean = false): Boolean {
    if (e.type != KeyEventType.KeyDown) return false
    val item = menus.flatMap { it.items }.filterNotNull().firstOrNull { it.shortcut?.matches(e) == true && it.shortcut.ctrl != plain } ?: return false
    if (item.enabled) item.run()
    return true
}

/**
 * The menu bar, drawn by Nozzle: the app's own type, colours and rounded menus. Click or Alt+letter opens a menu;
 * arrow keys move, Enter runs, Escape closes; with a menu open, pointing at another title switches to it.
 */
@Composable
fun NozzleMenuBar(menus: List<AppMenu>, openIndex: Int?, onOpen: (Int?) -> Unit) {
    val c = Nz.colors
    Row(Modifier.fillMaxWidth().height(34.dp).background(c.background).drawBehind {
        drawLine(c.line, androidx.compose.ui.geometry.Offset(0f, size.height - 0.5f), androidx.compose.ui.geometry.Offset(size.width, size.height - 0.5f), 1f)
    }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        menus.forEachIndexed { i, m ->
            val open = openIndex == i
            val hover = remember { MutableInteractionSource() }
            val hovered by hover.collectIsHoveredAsState()
            LaunchedEffect(hovered) { if (hovered && openIndex != null && openIndex != i) onOpen(i) }
            Box {
                Txt(m.title, Nz.type.label, if (open) c.accent else c.text,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (open) c.accent.copy(alpha = 0.16f) else if (hovered) c.surfaceRaised else c.background)
                        .hoverable(hover).clickable { onOpen(if (open) null else i) }.padding(horizontal = 12.dp, vertical = 6.dp)
                        .semantics { contentDescription = "${m.title} menu" })
                if (open) MenuDropdown(m, onClose = { onOpen(null) }, onMove = { d -> onOpen((i + d + menus.size) % menus.size) })
            }
        }
    }
}

@Composable
private fun MenuDropdown(menu: AppMenu, onClose: () -> Unit, onMove: (Int) -> Unit) {
    val c = Nz.colors
    val actions = menu.items.filterNotNull()
    var focus by remember(menu) { mutableStateOf(actions.indexOfFirst { it.enabled }.coerceAtLeast(0)) }
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    val below = with(androidx.compose.ui.platform.LocalDensity.current) { 32.dp.roundToPx() }
    Popup(offset = androidx.compose.ui.unit.IntOffset(0, below), onDismissRequest = onClose, properties = PopupProperties(focusable = true),
        onKeyEvent = { e ->
            if (e.type != KeyEventType.KeyDown) false else when (e.key) {
                Key.Escape -> { onClose(); true }
                Key.DirectionDown -> { focus = nextEnabled(actions, focus, 1); true }
                Key.DirectionUp -> { focus = nextEnabled(actions, focus, -1); true }
                Key.DirectionLeft -> { onMove(-1); true }
                Key.DirectionRight -> { onMove(1); true }
                Key.Enter, Key.Spacebar -> { actions.getOrNull(focus)?.takeIf { it.enabled }?.let { onClose(); it.run() }; true }
                else -> false
            }
        }) {
        Column(Modifier.width(IntrinsicSize.Max).widthIn(min = 240.dp).clip(RoundedCornerShape(12.dp)).background(c.surfaceRaised)
            .border(1.dp, c.lineStrong, RoundedCornerShape(12.dp)).padding(6.dp).focusRequester(focusRequester).focusable()) {
            var index = 0
            menu.items.forEach { item ->
                if (item == null) Box(Modifier.fillMaxWidth().padding(vertical = 5.dp, horizontal = 6.dp).height(1.dp).background(c.line))
                else {
                    val i = index++
                    val hover = remember { MutableInteractionSource() }
                    val hovered by hover.collectIsHoveredAsState()
                    LaunchedEffect(hovered) { if (hovered && item.enabled) focus = i }
                    val active = focus == i && item.enabled
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (active) c.accent.copy(alpha = 0.18f) else c.surfaceRaised)
                        .hoverable(hover).clickable(enabled = item.enabled) { onClose(); item.run() }.padding(horizontal = 12.dp, vertical = 7.dp)
                        .semantics { contentDescription = item.label + (item.shortcut?.let { ", ${it.text}" } ?: "") },
                        verticalAlignment = Alignment.CenterVertically) {
                        Txt(item.label, Nz.type.body, if (item.enabled) c.text else c.textMuted.copy(alpha = 0.6f), modifier = Modifier.weight(1f), maxLines = 1)
                        item.shortcut?.let { Spacer(Modifier.width(24.dp)); Txt(it.text, Nz.type.bodySmall, c.textMuted.copy(alpha = if (item.enabled) 1f else 0.5f), maxLines = 1) }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
}

private fun nextEnabled(items: List<MenuAction>, from: Int, step: Int): Int {
    var i = from
    repeat(items.size) { i = (i + step + items.size) % items.size; if (items[i].enabled) return i }
    return from
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
