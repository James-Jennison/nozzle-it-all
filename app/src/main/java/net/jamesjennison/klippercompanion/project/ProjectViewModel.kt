package net.jamesjennison.klippercompanion.project

import android.content.Context
import android.net.Uri
import java.io.File
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.jamesjennison.klippercompanion.MaterialProfile
import net.jamesjennison.klippercompanion.ModelTransform
import net.jamesjennison.klippercompanion.PaintCodec
import net.jamesjennison.klippercompanion.PaintStroke
import net.jamesjennison.klippercompanion.ShapeVolume
import net.jamesjennison.klippercompanion.VolumeCodec
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
    // _objects holds every object of the project across all plates; `objects` is the active plate's.
    private val _objects = MutableStateFlow<List<ProjectObject>>(emptyList())
    private val _visible = MutableStateFlow<List<ProjectObject>>(emptyList())
    val objects: StateFlow<List<ProjectObject>> = _visible.asStateFlow()
    val allObjects: StateFlow<List<ProjectObject>> = _objects.asStateFlow()
    private val _plates = MutableStateFlow<List<Plate>>(emptyList())
    val plates: StateFlow<List<Plate>> = _plates.asStateFlow()
    private val _activePlateId = MutableStateFlow<String?>(null)
    val activePlateId: StateFlow<String?> = _activePlateId.asStateFlow()

    fun plateIdOf(obj: ProjectObject): String? = obj.plateId ?: _plates.value.firstOrNull()?.id
    private fun publish() { _visible.value = _objects.value.filter { plateIdOf(it) == _activePlateId.value } }

    fun selectPlate(plateId: String) { if (_plates.value.any { it.id == plateId }) { _activePlateId.value = plateId; publish() } }

    suspend fun addPlate(): Plate {
        val current = _project.value ?: error("No project open.")
        val next = (_plates.value.maxOfOrNull { it.position } ?: -1) + 1
        val plate = Plate(UUID.randomUUID().toString(), current.id, next, "Plate ${next + 1}")
        dao.upsertPlates(listOf(plate))
        _plates.value = _plates.value + plate
        _activePlateId.value = plate.id
        touch()
        return plate
    }

    /** Only an empty plate can be removed, and never the last one. */
    suspend fun removePlate(plateId: String): Boolean {
        val target = _plates.value.find { it.id == plateId } ?: return false
        if (_plates.value.size <= 1 || _objects.value.any { plateIdOf(it) == plateId }) return false
        dao.deletePlate(target)
        _plates.value = _plates.value - target
        if (_activePlateId.value == plateId) _activePlateId.value = _plates.value.first().id
        touch()
        return true
    }

    suspend fun moveObjectToPlate(objectId: String, plateId: String) {
        if (_plates.value.none { it.id == plateId }) return
        val target = _objects.value.find { it.id == objectId } ?: return
        if (plateIdOf(target) == plateId) return
        record()
        val moved = target.copy(plateId = plateId)
        dao.upsertObjects(listOf(moved))
        _objects.value = _objects.value.map { if (it.id == objectId) moved else it }
        touch()
    }

    private suspend fun loadPlates(projectId: String) {
        var plates = dao.getPlatesForProject(projectId)
        if (plates.isEmpty()) { plates = listOf(Plate(UUID.randomUUID().toString(), projectId, 0, "Plate 1")); dao.upsertPlates(plates) }
        _plates.value = plates
        _activePlateId.value = plates.first().id
    }

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
        loadPlates(created.id); publish()
        history.clear(); publishUndoState()
        return created
    }

    /** A project holding the generated calibration model for [spec]; the spec drives overrides and post-processing at slice time. */
    suspend fun newCalibrationProject(spec: net.jamesjennison.klippercompanion.CalibrationSpec): Project {
        val created = newProject(spec.kind.label)
        val withSpec = created.copy(calibration = spec.encode())
        dao.updateProject(withSpec); _project.value = withSpec
        val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            File(context.cacheDir, "calibration-${UUID.randomUUID()}.stl").also { net.jamesjennison.klippercompanion.MeshEdit.writeBinaryStl(net.jamesjennison.klippercompanion.Calibration.mesh(spec), it) }
        }
        try { addObject(Uri.fromFile(file)) } finally { file.delete() }
        history.clear(); publishUndoState()
        return withSpec
    }

    suspend fun loadProject(id: String): Boolean {
        val loaded = dao.loadProjectWithObjects(id) ?: return false
        _project.value = loaded.first
        _objects.value = loaded.second
        loadPlates(loaded.first.id); publish()
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
        val added = ProjectObject(id = objectId, projectId = current.id, sourceFileUri = Uri.fromFile(localFile).toString(), plateId = _activePlateId.value).withMaterial(projectMaterial)
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

    /** Replaces an object's model with an edited mesh (mirror, lay flat, orient). One undo step; the old file is kept for undo. */
    suspend fun replaceObjectMesh(objectId: String, mesh: net.jamesjennison.klippercompanion.TriMesh) {
        val project = _project.value ?: return
        val target = _objects.value.find { it.id == objectId } ?: return
        val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            ProjectFileStore.newModelFile(context, project.id, "stl").also { net.jamesjennison.klippercompanion.MeshEdit.writeBinaryStl(mesh, it) }
        }
        record()
        val updated = target.copy(sourceFileUri = Uri.fromFile(file).toString(), paintJson = null, volumesJson = null) // strokes/volumes belong to the old mesh
        dao.upsertObjects(listOf(updated))
        _objects.value = _objects.value.map { if (it.id == objectId) updated else it }
        touch()
    }

    /**
     * Replaces [objectId] with the cut halves. The first non-null half keeps the object's id and placement; the other
     * becomes a new object beside it (shifted [gapMm] along X, same plate and material). One undo step.
     */
    suspend fun cutObject(objectId: String, lower: net.jamesjennison.klippercompanion.TriMesh?, upper: net.jamesjennison.klippercompanion.TriMesh?, gapMm: Float): List<ProjectObject> {
        val project = _project.value ?: return emptyList()
        val target = _objects.value.find { it.id == objectId } ?: return emptyList()
        val halves = listOfNotNull(lower, upper)
        if (halves.isEmpty()) return emptyList()
        val files = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            halves.map { mesh -> ProjectFileStore.newModelFile(context, project.id, "stl").also { net.jamesjennison.klippercompanion.MeshEdit.writeBinaryStl(mesh, it) } }
        }
        record()
        val first = target.copy(sourceFileUri = Uri.fromFile(files[0]).toString(), paintJson = null, volumesJson = null)
        val added = files.drop(1).mapIndexed { i, f -> target.copy(id = UUID.randomUUID().toString(), sourceFileUri = Uri.fromFile(f).toString(), paintJson = null, volumesJson = null, offsetXMm = target.offsetXMm + gapMm * (i + 1)) }
        dao.upsertObjects(listOf(first) + added)
        _objects.value = _objects.value.map { if (it.id == objectId) first else it } + added
        touch()
        return listOf(first) + added
    }

    /** Appends brush strokes to an object (a drag's dabs coalesce into one undo step). */
    suspend fun addPaintStrokes(objectId: String, strokes: List<PaintStroke>) {
        val target = _objects.value.find { it.id == objectId } ?: return
        val existing = PaintCodec.decode(target.paintJson)
        if (strokes.isEmpty() || existing.size >= PaintCodec.MAX_STROKES) return
        record(coalesceKey = "paint:$objectId")
        val updated = target.copy(paintJson = PaintCodec.encode((existing + strokes).takeLast(PaintCodec.MAX_STROKES)))
        dao.upsertObjects(listOf(updated))
        _objects.value = _objects.value.map { if (it.id == objectId) updated else it }
        touch()
    }

    suspend fun clearPaint(objectId: String) {
        val target = _objects.value.find { it.id == objectId } ?: return
        if (target.paintJson == null) return
        record()
        val updated = target.copy(paintJson = null)
        dao.upsertObjects(listOf(updated))
        _objects.value = _objects.value.map { if (it.id == objectId) updated else it }
        touch()
    }

    suspend fun setVolumes(objectId: String, volumes: List<ShapeVolume>) {
        val target = _objects.value.find { it.id == objectId } ?: return
        record(coalesceKey = "volumes:$objectId")
        val updated = target.copy(volumesJson = volumes.take(VolumeCodec.MAX_VOLUMES).takeIf { it.isNotEmpty() }?.let(VolumeCodec::encode))
        dao.upsertObjects(listOf(updated))
        _objects.value = _objects.value.map { if (it.id == objectId) updated else it }
        touch()
    }

    /** Writes the whole project (every plate) as a .nozzleproj archive. */
    fun exportArchive(out: java.io.OutputStream) {
        val project = _project.value ?: error("No project open.")
        ProjectArchive.write(project.name, _plates.value, _objects.value, { File(Uri.parse(it.sourceFileUri).path!!) }, out)
    }

    /** Imports an archive as a brand-new project (new ids everywhere) and opens it. */
    suspend fun importArchive(input: java.io.InputStream): Project {
        val staging = File(context.cacheDir, "import-${UUID.randomUUID()}").also { it.mkdirs() }
        try {
            val parsed = ProjectArchive.read(input, staging)
            val now = System.currentTimeMillis()
            val project = Project(UUID.randomUUID().toString(), parsed.name, now, now)
            val plateIds = parsed.plates.sortedBy { it.position }.associate { it.id to UUID.randomUUID().toString() }
            val plates = parsed.plates.sortedBy { it.position }.mapIndexed { i, p -> Plate(plateIds.getValue(p.id), project.id, i, p.name) }
            val objectIds = parsed.objects.associate { it.id to UUID.randomUUID().toString() }
            val objects = parsed.objects.map { o ->
                val id = objectIds.getValue(o.id)
                val extension = o.file.substringAfterLast('.').lowercase()
                val local = ProjectFileStore.importObject(context, project.id, id, Uri.fromFile(File(staging, o.file)), extension)
                ProjectObject(id, project.id, Uri.fromFile(local).toString(), o.plateId?.let(plateIds::get), o.offsetXMm, o.offsetYMm, o.rotationZDeg, o.scale,
                    o.materialId, o.materialDisplayName, o.materialTempNozzleC, o.materialTempBedC, o.toolSlotIndex, o.paintJson, o.volumesJson)
            }
            dao.upsertProject(project)
            if (plates.isNotEmpty()) dao.upsertPlates(plates)
            if (objects.isNotEmpty()) dao.upsertObjects(objects)
            loadProject(project.id)
            return project
        } finally { staging.deleteRecursively() }
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
        _objects.value = emptyList(); _plates.value = emptyList(); _activePlateId.value = null; publish()
    }

    private suspend fun touch() {
        publish()
        val current = _project.value ?: return
        val updated = current.copy(modifiedAt = System.currentTimeMillis())
        dao.updateProject(updated)
        _project.value = updated
    }
}
