package net.jamesjennison.klippercompanion

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.jamesjennison.klippercompanion.project.AppDatabase
import net.jamesjennison.klippercompanion.project.ProjectObject
import net.jamesjennison.klippercompanion.project.ProjectViewModel

/**
 * Phase 1 (Consumer Slicer Plan §16): the real saved-project editor - opens an existing project
 * (or a freshly created one, [newProjectName]) via [ProjectViewModel], lets the owner add/
 * duplicate/remove objects on the build plate, and shows/edits their placement through
 * [ProjectWorkspace] (tap to select, drag/pinch/rotate the selected object). Deliberately does
 * NOT touch the existing single-object share-intent flow (SliceAndPrintPanel.kt) - this is a
 * separate, additive entry point (a "Projects" section under the Files tab), matching this
 * session's own owner-confirmed choice to keep that already-tested flow untouched rather than
 * retrofit it. Slicing a project (multi-object, via engine::slice_multi_object) is still open -
 * see docs/WORK_ORDER.md's WO-17 entry.
 */
@Composable fun ProjectEditorScreen(projectId: String?, newProjectName: String?, close: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // remember(projectId) matches this codebase's existing per-screen-instance state convention
    // (SliceAndPrintPanel's own remember(uri)) rather than an androidx ViewModel scoped to the
    // Activity, which would otherwise retain state across switching between different projects.
    val vm = remember(projectId, newProjectName) { ProjectViewModel(context.applicationContext, AppDatabase.get(context.applicationContext).projectDao()) }
    var selectedId by remember(projectId, newProjectName) { mutableStateOf<String?>(null) }
    var geometry by remember(projectId, newProjectName) { mutableStateOf<Map<String, MeshGeometry>>(emptyMap()) }
    var addError by remember(projectId, newProjectName) { mutableStateOf<String?>(null) }
    var loadError by remember(projectId, newProjectName) { mutableStateOf<String?>(null) }
    var ready by remember(projectId, newProjectName) { mutableStateOf(false) }

    LaunchedEffect(projectId, newProjectName) {
        ready = false
        loadError = null
        if (projectId != null) {
            if (!vm.loadProject(projectId)) loadError = "This project could not be found."
        } else if (newProjectName != null) {
            vm.newProject(newProjectName)
        }
        ready = true
    }

    val project by vm.project.collectAsState()
    val objects by vm.objects.collectAsState()

    // Loads each object's mesh geometry exactly once per source file - a transform-only change
    // to `objects` re-triggers this effect but every already-loaded id is skipped, so it never
    // re-parses a file just because its placement moved (ProjectGLRenderer separately relies on
    // this same "same geometry reference" stability to skip re-uploading its own VBO).
    LaunchedEffect(objects) {
        val missing = objects.filter { it.id !in geometry }
        if (missing.isEmpty()) return@LaunchedEffect
        val loaded = HashMap<String, MeshGeometry>()
        for (obj in missing) {
            val path = Uri.parse(obj.sourceFileUri).path ?: continue
            try { loaded[obj.id] = MeshLoader.load(path) } catch (_: Exception) { /* surfaced per-object below via a missing entry */ }
        }
        if (loaded.isNotEmpty()) geometry = geometry + loaded
    }

    val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            addError = null
            try { selectedId = vm.addObject(uri).id } catch (e: Exception) { addError = e.message ?: "Could not add that file." }
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(project?.name ?: "Project", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).testTag("project-editor-title"))
                IconButton(close, Modifier.testTag("project-editor-close")) { CompanionIcon(CompanionSymbol.CLOSE) }
            }
            when {
                !ready -> Column(Modifier.weight(1f).fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator() }
                loadError != null -> Column(Modifier.weight(1f).fillMaxWidth().padding(16.dp)) { Text(loadError!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("project-editor-error")) }
                else -> Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val workspaceObjects = objects.mapNotNull { obj -> geometry[obj.id]?.let { WorkspaceObject(obj, it) } }
                    ProjectWorkspace(
                        objects = workspaceObjects,
                        selectedId = selectedId,
                        onSelect = { selectedId = it },
                        onTransformChange = { id, transform -> scope.launch { vm.updateObjectTransform(id, transform) } },
                    )
                    val missingGeometryCount = objects.size - workspaceObjects.size
                    if (missingGeometryCount > 0) Text("Loading $missingGeometryCount model(s)…", style = MaterialTheme.typography.bodySmall)

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ pickModel.launch(arrayOf("*/*")) }, modifier = Modifier.testTag("project-add-object")) { Text("Add model") }
                        OutlinedButton({ selectedId?.let { id -> scope.launch { selectedId = vm.duplicateObject(id)?.id } } }, enabled = selectedId != null, modifier = Modifier.testTag("project-duplicate-object")) { Text("Duplicate") }
                        OutlinedButton({ selectedId?.let { id -> scope.launch { vm.removeObject(id); selectedId = null } } }, enabled = selectedId != null, modifier = Modifier.testTag("project-remove-object")) { Text("Remove") }
                    }
                    addError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

                    Text("Objects on this plate", style = MaterialTheme.typography.titleSmall)
                    if (objects.isEmpty()) Text("No objects yet - add an STL, 3MF or OBJ model to start this project's build plate.", style = MaterialTheme.typography.bodySmall)
                    objects.forEach { obj -> ProjectObjectRow(obj, selected = obj.id == selectedId, onClick = { selectedId = obj.id }) }
                }
            }
        }
    }
}

@Composable private fun ProjectObjectRow(obj: ProjectObject, selected: Boolean, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().testTag("project-object-${obj.id}"),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(Uri.parse(obj.sourceFileUri).lastPathSegment ?: obj.id, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick) { Text(if (selected) "Selected" else "Select") }
        }
    }
}
