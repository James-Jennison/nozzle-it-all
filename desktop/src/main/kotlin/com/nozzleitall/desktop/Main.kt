package com.nozzleitall.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
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
import com.nozzleitall.desktop.workspace.AdvancedWorkspace
import com.nozzleitall.desktop.workspace.WorkspaceScreen
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
    WORKSPACE("Advanced Workspace", NzIcon.WORKSPACE, Key.Seven),
    SETTINGS("Settings", NzIcon.SETTINGS, Key.Comma),
}

class AppState(val paths: AppPaths, val scope: CoroutineScope) {
    val fleet = Fleet(paths, scope) { System.err.println(it) }
    val library = ProjectLibrary(paths)
    val prepare = PrepareState(this)
    val workspace = AdvancedWorkspace(paths)
    var destination by mutableStateOf(Destination.FLEET)
    var selectedPrinter by mutableStateOf<String?>(null)
    val version: String = System.getProperty("nozzle.version") ?: "0.1.0-dev"

    fun openPrinter(id: String) { selectedPrinter = id; destination = Destination.MONITOR }
}

fun main() = application {
    val paths = remember { AppPaths.resolve().ensure() }
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    val state = remember { AppState(paths, scope) }
    Window(onCloseRequest = { state.fleet.shutdown(); exitApplication() }, title = Glossary.PRODUCT_FAMILY,
        icon = painterResource("brand/mark-violet.svg"), state = rememberWindowState(size = DpSize(1360.dp, 860.dp)),
        onPreviewKeyEvent = { e ->
            // Ctrl+1..7 and Ctrl+, move between places, like other desktop apps; plain keys stay with text fields.
            if (e.type == KeyEventType.KeyDown && e.isCtrlPressed) Destination.entries.firstOrNull { it.key == e.key }?.let { state.destination = it; true } ?: false else false
        }) {
        window.minimumSize = java.awt.Dimension(960, 640)
        NozzleTheme(state.fleet.settings.value.theme) { App(state) }
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
                Destination.WORKSPACE -> WorkspaceScreen(state)
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
        Destination.entries.filter { it != Destination.WORKSPACE && it != Destination.SETTINGS }.forEach { RailItem(it, state) }
        Spacer(Modifier.weight(1f))
        // The specialist workspace sits apart from everyday places, behind a divider, so entering it is a deliberate choice.
        Box(Modifier.width(48.dp).height(1.dp).background(c.line))
        Spacer(Modifier.height(6.dp))
        RailItem(Destination.WORKSPACE, state)
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
