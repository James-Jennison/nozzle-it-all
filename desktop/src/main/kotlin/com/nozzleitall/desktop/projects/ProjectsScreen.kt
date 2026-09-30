package com.nozzleitall.desktop.projects

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nozzleitall.desktop.AppState
import com.nozzleitall.desktop.Destination
import com.nozzleitall.desktop.prepare.chooseFiles
import com.nozzleitall.desktop.ui.*
import java.io.File
import java.text.DateFormat
import java.util.Date

@Composable
fun ProjectsScreen(state: AppState) {
    val c = Nz.colors
    var refresh by remember { mutableStateOf(0) }
    val projects = remember(refresh) { state.library.list() }
    var notice by remember { mutableStateOf<Pair<String, File?>?>(null) }
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        SectionHeader("Projects", "Saved as standard 3MF files, so Android, the Web app and other slicers can open them.") {
            NzButton("Import", {
                chooseFiles("Import models or projects", listOf("stl", "3mf", "obj")).forEach { f ->
                    runCatching { state.library.import(f, state.version) }.onFailure { notice = "Couldn't import ${f.name}: ${it.message}" to null }
                }
                refresh++
            }, icon = NzIcon.IMPORT)
            NzButton("New project", { state.prepare.newProject(); state.destination = Destination.PREPARE }, kind = ButtonKind.PRIMARY, icon = NzIcon.ADD)
        }
        notice?.let { (text, trashed) ->
            Banner(text, if (trashed != null) BannerKind.INFO else BannerKind.WARNING, if (trashed != null) "Undo" to {
                runCatching { state.library.restore(trashed) }; notice = null; refresh++ } else "Dismiss" to { notice = null })
        }
        if (projects.isEmpty()) Card(Modifier.fillMaxWidth()) {
            EmptyState(NzIcon.PROJECTS, "No projects yet", "Start a new project, or import an STL, OBJ or 3MF file. Projects stay on this computer in ${state.library.dir.absolutePath}.") {
                NzButton("New project", { state.prepare.newProject(); state.destination = Destination.PREPARE }, kind = ButtonKind.PRIMARY)
            }
        } else LazyVerticalGrid(GridCells.Adaptive(300.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(projects, key = { it.file.absolutePath }) { p ->
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(NzIcon.PROJECTS, c.accent, 22.dp); Spacer(Modifier.width(10.dp))
                        Txt(p.name, Nz.type.title, modifier = Modifier.weight(1f), maxLines = 1)
                    }
                    Txt("${p.objectCount} object${if (p.objectCount == 1) "" else "s"}${p.printerModel?.let { " · for $it" } ?: ""}", Nz.type.bodySmall, c.textMuted)
                    Txt("Changed ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(p.modifiedMillis))}", Nz.type.bodySmall, c.textMuted)
                    p.problem?.let { Txt(it, Nz.type.bodySmall, Nz.status.paused) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NzButton("Open", {
                            runCatching { state.prepare.open(p.file) }.onSuccess { state.destination = Destination.PREPARE }.onFailure { notice = "Couldn't open ${p.name}: ${it.message}" to null }
                        }, kind = ButtonKind.PRIMARY, enabled = p.objectCount > 0)
                        NzButton("Move to trash", { runCatching { state.library.moveToTrash(p.file) }.onSuccess { notice = "${p.name} moved to the trash." to it }; refresh++ }, kind = ButtonKind.QUIET)
                    }
                }
            }
        }
    }
}
