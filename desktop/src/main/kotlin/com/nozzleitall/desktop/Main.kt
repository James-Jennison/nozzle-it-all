package com.nozzleitall.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import java.io.File
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.nozzleitall.desktop.prepare.PrepareScreen
import com.nozzleitall.desktop.prepare.PrepareState
import com.nozzleitall.desktop.projects.ProjectLibrary
import com.nozzleitall.desktop.projects.ProjectsScreen
import com.nozzleitall.desktop.screens.*
import com.nozzleitall.desktop.ui.*
import com.nozzleitall.printer.Glossary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** The places in the app. Printers come first: Nozzle is organised around the printers you own, not a settings tree. */
enum class Destination(val label: String, val icon: NzIcon, val key: Key) {
    FLEET("Printers", NzIcon.FLEET, Key.One),
    PROJECTS("Projects", NzIcon.PROJECTS, Key.Two),
    PREPARE("Prepare", NzIcon.PREPARE, Key.Three),
    MONITOR("Print & Monitor", NzIcon.MONITOR, Key.Four),
    MATERIALS("Materials & Toolheads", NzIcon.MATERIALS, Key.Five),
    SPECTRUM("Full Spectrum", NzIcon.SPECTRUM, Key.Six),
    SETTINGS("Settings", NzIcon.SETTINGS, Key.Comma),
}

class AppState(val paths: AppPaths, val scope: CoroutineScope) {
    val fleet = Fleet(paths, scope, log = { System.err.println(it) })
    val library = ProjectLibrary(paths)
    val prepare = PrepareState(this)
    /** Off unless the user turns it on in Settings: lets Nozzle It All Web on this computer reach these printers. */
    val connector = com.nozzleitall.desktop.connector.LocalConnector(fleet, java.io.File(paths.config, "connector-tokens.json"))
    var destination by mutableStateOf(Destination.FLEET)
    var selectedPrinter by mutableStateOf<String?>(null)
    val version: String = System.getProperty("nozzle.version") ?: "0.1.0-dev"
    var showAbout by mutableStateOf(false)
    /** Which menu is open in the menu bar, if any. */
    var openMenu by mutableStateOf<Int?>(null)
    /** An action waiting for "discard unsaved changes?" to be answered. */
    var pendingDiscard by mutableStateOf<(() -> Unit)?>(null)

    fun openPrinter(id: String) { selectedPrinter = id; destination = Destination.MONITOR }
}

/**
 * Names the window class "nozzle-it-all" on Linux (X11 and XWayland), so the dock and task switcher match windows to the
 * installed launcher (StartupWMClass) and show the Nozzle It All icon instead of a generic one. Java otherwise uses the
 * main class name. Must run before the first window is created.
 */
private fun setLinuxWindowClass() {
    if (!System.getProperty("os.name").orEmpty().startsWith("Linux")) return
    runCatching {
        val toolkit = java.awt.Toolkit.getDefaultToolkit()
        val field = toolkit.javaClass.getDeclaredField("awtAppClassName")
        field.isAccessible = true
        field.set(toolkit, "nozzle-it-all")
    }
}

/** Files opened with Nozzle It All (from the file manager or the command line): a 3MF opens as a project, models are added. */
fun main(args: Array<String>) { setLinuxWindowClass(); runApp(args.map(::File).filter { it.isFile }) }

private fun runApp(open: List<File>) = application {
    val paths = remember { AppPaths.resolve().ensure() }
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    val state = remember { AppState(paths, scope) }
    LaunchedEffect(Unit) {
        if (open.isEmpty()) return@LaunchedEffect
        val (projects, models) = open.partition { it.extension.equals("3mf", true) && open.size == 1 }
        runCatching { projects.firstOrNull()?.let(state.prepare::open); models.forEach(state.prepare::importModel) }.onFailure { state.prepare.notice = it.message }
        state.destination = Destination.PREPARE
    }
    val quit = { state.connector.stop(); state.fleet.shutdown(); exitApplication() }
    Window(onCloseRequest = quit, title = Glossary.PRODUCT_FAMILY,
        icon = painterResource("brand/mark-violet.svg"), state = rememberWindowState(size = DpSize(1360.dp, 860.dp)),
        onPreviewKeyEvent = { e ->
            // Menu shortcuts (all with Ctrl, so typing is never taken) and Alt+letter to open a menu.
            val menus = appMenus(state, quit)
            if (e.type == KeyEventType.KeyDown && e.isAltPressed && !e.isCtrlPressed) menus.indexOfFirst { it.mnemonic == e.key }.takeIf { it >= 0 }?.let { state.openMenu = it; true } ?: false
            else handleMenuShortcut(menus, e)
        },
        // Keys no focused control used (a text field keeps its Delete): plain shortcuts such as Delete for the selected object.
        onKeyEvent = { e -> handleMenuShortcut(appMenus(state, quit), e, plain = true) }) {
        window.minimumSize = java.awt.Dimension(960, 640)
        NozzleTheme(state.fleet.settings.value.theme) {
            Column(Modifier.fillMaxSize()) {
                NozzleMenuBar(appMenus(state, quit), state.openMenu) { state.openMenu = it }
                App(state)
            }
            if (state.showAbout) AboutWindow(state) { state.showAbout = false }
            state.pendingDiscard?.let { action ->
                ConfirmDialog("Discard unsaved changes?", "${state.prepare.name} has changes that aren't saved.", "Discard changes", destructive = true,
                    onConfirm = { state.pendingDiscard = null; action() }, onDismiss = { state.pendingDiscard = null })
            }
        }
    }
}

@Composable
fun App(state: AppState) {
    val c = Nz.colors
    Row(Modifier.fillMaxSize().background(c.background)) {
        NavigationRail(state)
        Box(Modifier.fillMaxSize().semantics { paneTitle = state.destination.label }) {
            when (state.destination) {
                Destination.FLEET -> FleetScreen(state)
                Destination.PROJECTS -> ProjectsScreen(state)
                Destination.PREPARE -> PrepareScreen(state)
                Destination.MONITOR -> MonitorScreen(state)
                Destination.MATERIALS -> MaterialsScreen(state)
                Destination.SPECTRUM -> FullSpectrumScreen(state)
                Destination.SETTINGS -> SettingsScreen(state)
            }
        }
    }
}

@Composable
private fun NavigationRail(state: AppState) {
    val c = Nz.colors
    Column(Modifier.width(96.dp).fillMaxHeight().background(c.surface).border(1.dp, c.line).padding(vertical = 14.dp)
        .semantics { contentDescription = "Main navigation" }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        androidx.compose.foundation.Image(painterResource("brand/mark-violet.svg"), contentDescription = Glossary.PRODUCT_FAMILY, modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(14.dp))
        // Places appear when some printer can use them: Full Spectrum only if a printer reports that extension.
        val spectrum = state.fleet.printers.values.any { it.capabilities.value?.vendorExtensions?.contains(com.nozzleitall.printer.ext.Snapmaker.FULL_SPECTRUM) == true }
        Destination.entries.filter { it != Destination.SETTINGS && (it != Destination.SPECTRUM || spectrum) }.forEach { RailItem(it, state) }
        Spacer(Modifier.weight(1f))
        Box(Modifier.width(48.dp).height(1.dp).background(c.line))
        Spacer(Modifier.height(6.dp))
        RailItem(Destination.SETTINGS, state)
    }
}

@Composable
private fun RailItem(d: Destination, state: AppState) {
    val c = Nz.colors
    val selected = state.destination == d
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.width(84.dp).clip(shape).background(if (selected) c.surfaceRaised else androidx.compose.ui.graphics.Color.Transparent)
        .focusRing(focused, c.focus, shape)
        .selectable(selected, interaction, null, role = Role.Tab) { state.destination = d }
        .padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(width = 48.dp, height = 30.dp).clip(RoundedCornerShape(15.dp)).background(if (selected) c.accent.copy(alpha = 0.18f) else androidx.compose.ui.graphics.Color.Transparent),
            contentAlignment = Alignment.Center) { Icon(d.icon, if (selected) c.accent else c.textMuted, 22.dp) }
        Txt(d.label, Nz.type.bodySmall.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center), if (selected) c.text else c.textMuted, maxLines = 2)
    }
}
