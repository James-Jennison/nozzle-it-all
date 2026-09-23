package net.jamesjennison.klippercompanion.project

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.jamesjennison.klippercompanion.MaterialProfile
import net.jamesjennison.klippercompanion.ModelTransform
import net.jamesjennison.klippercompanion.sliceableModelName
import java.util.UUID

// Phase 1 (Consumer Slicer Plan §16): the real state owner for a multi-object build plate -
// SliceAndPrintPanel.kt's own state (still `remember(uri)`, still single-object) moves onto this
// once the Prepare tab's Model/Arrange steps are rebuilt around it. Kept independent of that UI
// migration so the Room round-trip itself (add/duplicate/remove/transform, surviving simulated
// process death) can be built and verified on its own first - see ProjectViewModelDeviceTest.
class ProjectViewModel(private val context: Context, private val dao: ProjectDao) : ViewModel() {
    private val _project = MutableStateFlow<Project?>(null)
    val project: StateFlow<Project?> = _project.asStateFlow()
    private val _objects = MutableStateFlow<List<ProjectObject>>(emptyList())
    val objects: StateFlow<List<ProjectObject>> = _objects.asStateFlow()

    private val history = UndoHistory<List<ProjectObject>>()
    private val _undoState = MutableStateFlow(false to false)
    /** (canUndo, canRedo) */
    val undoState: StateFlow<Pair<Boolean, Boolean>> = _undoState.asStateFlow()
    private fun record(coalesceKey: Any? = null) { history.record(_objects.value, coalesceKey); publishUndoState() }
    private fun publishUndoState() { _undoState.value = history.canUndo to history.canRedo }

    suspend fun undo(): Boolean = restore(history.undo(_objects.value))
    suspend fun redo(): Boolean = restore(history.redo(_objects.value))
    private suspend fun restore(target: List<ProjectObject>?): Boolean {
        publishUndoState()
        target ?: return false
        val keep = target.map { it.id }.toSet()
        _objects.value.filter { it.id !in keep }.forEach { dao.deleteObject(it) }
        if (target.isNotEmpty()) dao.upsertObjects(target)
        _objects.value = target
        touch()
        return true
    }

    suspend fun newProject(name: String): Project {
        val now = System.currentTimeMillis()
        val created = Project(id = UUID.randomUUID().toString(), name = name, createdAt = now, modifiedAt = now)
        dao.upsertProject(created)
        _project.value = created
        _objects.value = emptyList()
        history.clear(); publishUndoState()
        return created
    }

    suspend fun loadProject(id: String): Boolean {
        val loaded = dao.loadProjectWithObjects(id) ?: return false
        _project.value = loaded.first
        _objects.value = loaded.second
        history.clear(); publishUndoState()
        // Files of removed objects are kept while undo could still bring them back; anything no
        // longer referenced when a project is (re)opened is orphaned for good.
        ProjectFileStore.pruneUnreferenced(context, loaded.first.id, loaded.second.map { it.sourceFileUri }.toSet())
        return true
    }

    // Copies `source` into this project's own persisted storage (ProjectFileStore, not
    // cacheDir - a saved project's files must survive process death, unlike
    // SliceAndPrintPanel's existing single-share-intent flow) and adds a real, persisted
    // ProjectObject row for it. Reuses sliceableModelName's own real STL/3MF/OBJ validation
    // (SlicingCoordinator.kt) rather than a second, parallel extension check.
    suspend fun addObject(source: Uri): ProjectObject {
        val current = _project.value ?: error("No project open.")
        val rawName = ProjectFileStore.displayName(context, source) ?: source.lastPathSegment.orEmpty()
        val validName = sliceableModelName(rawName)
        require(validName.isNotEmpty()) { "Unsupported file - use STL, 3MF or OBJ." }
        val extension = validName.substringAfterLast('.')
        val objectId = UUID.randomUUID().toString()
        val localFile = ProjectFileStore.importObject(context, current.id, objectId, source, extension)
        // Phase 3 (§11): a newly added object picks up whatever material the project's existing
        // objects already share - "single-material-per-project" (this phase's own scope) means a
        // material picked before this add still applies, not just objects added before it.
        val projectMaterial = _objects.value.firstOrNull()?.material()
        record()
        val added = ProjectObject(id = objectId, projectId = current.id, sourceFileUri = Uri.fromFile(localFile).toString()).withMaterial(projectMaterial)
        dao.upsertObjects(listOf(added))
        _objects.value = _objects.value + added
        touch()
        return added
    }

    // A real second ProjectObject row referencing the same already-imported model file - no
    // re-copy needed, only the placement differs. Offset on X so the copy isn't rendered
    // exactly on top of the original; real auto-arrange (Phase 1's own remaining scope) is
    // what actually resolves placement/overlap properly, this is just a sane, visible default.
    suspend fun duplicateObject(objectId: String): ProjectObject? {
        val original = _objects.value.find { it.id == objectId } ?: return null
        record()
        val duplicate = original.copy(id = UUID.randomUUID().toString(), offsetXMm = original.offsetXMm + 20f)
        dao.upsertObjects(listOf(duplicate))
        _objects.value = _objects.value + duplicate
        touch()
        return duplicate
    }

    suspend fun removeObject(objectId: String) {
        val current = _project.value ?: return
        val target = _objects.value.find { it.id == objectId } ?: return
        record()
        dao.deleteObject(target)
        _objects.value = _objects.value.filterNot { it.id == objectId }
        touch()
    }

    /** One undo step for a batch placement change (auto-arrange). */
    suspend fun updateTransforms(transforms: Map<String, ModelTransform>) {
        val changed = _objects.value.filter { it.id in transforms }.map { it.withTransform(transforms.getValue(it.id)) }
        if (changed.isEmpty()) return
        record()
        dao.upsertObjects(changed)
        val byId = changed.associateBy { it.id }
        _objects.value = _objects.value.map { byId[it.id] ?: it }
        touch()
    }

    suspend fun updateObjectTransform(objectId: String, transform: ModelTransform) {
        val target = _objects.value.find { it.id == objectId } ?: return
        record(coalesceKey = objectId)
        val updated = target.withTransform(transform)
        dao.upsertObjects(listOf(updated))
        _objects.value = _objects.value.map { if (it.id == objectId) updated else it }
        touch()
    }

    // Phase 3 (§11): applies one material to every object on the plate at once -
    // "single-material-per-project" is a real UI-level invariant this is the only place that
    // enforces, not a separate project-level column (ProjectObject.materialId/etc were already
    // planned ahead in the Phase 0 schema; this keeps that the one source of truth rather than
    // adding a second, possibly-diverging place the "current" material could live).
    suspend fun setProjectMaterial(material: MaterialProfile?) {
        record()
        val updated = _objects.value.map { it.withMaterial(material) }
        if (updated.isNotEmpty()) dao.upsertObjects(updated)
        _objects.value = updated
        touch()
    }

    // The project's current material, derived from its objects (all objects are kept in sync by
    // setProjectMaterial above, so the first one's value is authoritative) - null for an empty
    // project or one where no material has been picked yet.
    fun currentMaterial(): MaterialProfile? = _objects.value.firstOrNull()?.material()

    // Phase 8 (§11, §16, WO-25): real, genuine per-object material + tool-slot assignment -
    // unlike setProjectMaterial (which keeps every object in lockstep, the correct behavior for
    // every single-extruder target this app slices for today), this updates exactly one object,
    // letting different objects on the same plate diverge. Deliberately a separate method rather
    // than a mode flag on setProjectMaterial: callers (ProjectEditorScreen) decide which one to
    // offer based on the real target printer's own tool count (ToolSlots.kt), so a single-
    // extruder project's UI never even shows a path that could call this and quietly break the
    // "every object shares one material" invariant that UI still promises.
    suspend fun setObjectMaterial(objectId: String, material: MaterialProfile?, toolSlotIndex: Int?) {
        val target = _objects.value.find { it.id == objectId } ?: return
        record()
        val updated = target.withMaterial(material).withToolSlot(toolSlotIndex)
        dao.upsertObjects(listOf(updated))
        _objects.value = _objects.value.map { if (it.id == objectId) updated else it }
        touch()
    }

    suspend fun renameProject(name: String) {
        val current = _project.value ?: return
        val updated = current.copy(name = name, modifiedAt = System.currentTimeMillis())
        dao.updateProject(updated)
        _project.value = updated
    }

    suspend fun deleteProject() {
        val current = _project.value ?: return
        dao.deleteProject(current)
        ProjectFileStore.deleteProject(context, current.id)
        _project.value = null
        _objects.value = emptyList()
    }

    private suspend fun touch() {
        val current = _project.value ?: return
        val updated = current.copy(modifiedAt = System.currentTimeMillis())
        dao.updateProject(updated)
        _project.value = updated
    }
}
