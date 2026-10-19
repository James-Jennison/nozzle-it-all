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

/**
 * The places in the app. The Workspace is home: a model goes from plate to slice to send in one place, and a job bar on every
 * screen follows whatever is printing. Printers and Settings sit beside it; Projects is reached from the Workspace and the File menu.
 */
enum class Destination(val label: String, val icon: NzIcon, val key: Key, val inRail: Boolean = true) {
    PREPARE("Workspace", NzIcon.PREPARE, Key.One),
    FLEET("Printers", NzIcon.FLEET, Key.Two),
    ENVIRONMENT("Environment", NzIcon.HEAT, Key.Seven),
    PROJECTS("Projects", NzIcon.PROJECTS, Key.Three, inRail = false),
    // One printer's own page (a Monitor tab and a Materials tab). It is reached from a printer on the Printers page, so it
    // has no place of its own in the rail, where Printers stays lit while you are on it.
    MONITOR("Print & Monitor", NzIcon.MONITOR, Key.Four, inRail = false),
    MATERIALS("Materials & Toolheads", NzIcon.MATERIALS, Key.Five, inRail = false),
    HISTORY("History", NzIcon.LAYERS, Key.Six, inRail = false),
    SETTINGS("Settings", NzIcon.SETTINGS, Key.Comma);

    /** The rail item that is lit while this destination is shown. */
    val railItem: Destination get() = if (inRail) this else if (this == PROJECTS) PREPARE else FLEET
}

class AppState(val paths: AppPaths, val scope: CoroutineScope) {
    // Lazy IO on explicit discovery/panel entry; locally saved names, never keys.
    val goveeNames = com.nozzleitall.desktop.environment.DesktopGoveeNames(
        com.nozzleitall.environment.GoveeNameCache(File(paths.config, "environment/govee-names.json")))
    val environment = com.nozzleitall.environment.EnvironmentalCoordinator(
        File(paths.config, "environment/control.json"),
        com.nozzleitall.environment.EnvironmentalHistory(com.nozzleitall.environment.SqlStoreFactory {
            com.nozzleitall.desktop.environment.JdbcEnvironmentalStore(File(paths.data, "environment/history.db"))
        }), { com.nozzleitall.desktop.environment.LinuxBleTransport(System.getProperty("nozzle.environment.bluezAdapter")) },
        platformIdentity = "linux/${System.getProperty("os.version")}/${System.getProperty("os.arch")}",
        goveeNames = goveeNames::currentDevices,
        restoreGoveeNames = { goveeNames.cachedDevices(); Unit })
    val fleet = Fleet(paths, scope, log = { System.err.println(it) })
    val library = ProjectLibrary(paths)
    val prepare = PrepareState(this)
    /** Off unless the user turns it on in Settings: lets Nozzle It All Web on this computer reach these printers. */
    val connector = com.nozzleitall.desktop.connector.LocalConnector(fleet, java.io.File(paths.config, "connector-tokens.json"))
    var destination by mutableStateOf(Destination.PREPARE)
    var selectedPrinter by mutableStateOf<String?>(null)
    /** Set to open the Printers page already looking for printers on the network (the first-run path); the page clears it. */
    var findPrinterRequested by mutableStateOf(false)
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
    val quit = { state.environment.close(); state.connector.stop(); state.fleet.shutdown(); exitApplication() }
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
        DisposableEffect(window) {
            val listener = object : java.awt.event.WindowAdapter() {
                override fun windowDeactivated(e: java.awt.event.WindowEvent) { state.environment.foreground(false) }
                override fun windowActivated(e: java.awt.event.WindowEvent) { state.environment.foreground(true) }
                override fun windowIconified(e: java.awt.event.WindowEvent) { state.environment.foreground(false) }
                override fun windowDeiconified(e: java.awt.event.WindowEvent) { state.environment.foreground(true) }
                override fun windowClosed(e: java.awt.event.WindowEvent) { state.environment.foreground(false) }
            }
            state.environment.foreground(true)
            window.addWindowListener(listener)
            onDispose { window.removeWindowListener(listener); state.environment.foreground(false) }
        }
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
        Column(Modifier.fillMaxSize().semantics { paneTitle = state.destination.label }) {
          JobBar(state)
          Box(Modifier.weight(1f).fillMaxWidth()) {
            when (state.destination) {
                Destination.FLEET -> FleetScreen(state)
                Destination.PROJECTS -> ProjectsScreen(state)
                Destination.PREPARE -> PrepareScreen(state)
                Destination.MONITOR -> MonitorScreen(state)
                Destination.MATERIALS -> MaterialsScreen(state)
                Destination.HISTORY -> HistoryScreen(state)
                Destination.SETTINGS -> SettingsScreen(state)
                Destination.ENVIRONMENT -> EnvironmentalScreen(state)
            }
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
        // Full Spectrum isn't a place of its own: its colour mixing is part of the Filament card in Prepare.
        Destination.entries.filter { it.inRail && it != Destination.SETTINGS }.forEach { RailItem(it, state) }
        Spacer(Modifier.weight(1f))
        Box(Modifier.width(48.dp).height(1.dp).background(c.line))
        Spacer(Modifier.height(6.dp))
        RailItem(Destination.SETTINGS, state)
    }
}

@Composable
private fun RailItem(d: Destination, state: AppState) {
    val c = Nz.colors
    val selected = state.destination.railItem == d
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
