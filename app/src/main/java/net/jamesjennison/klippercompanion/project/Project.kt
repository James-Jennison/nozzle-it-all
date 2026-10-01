package net.jamesjennison.klippercompanion.project

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import net.jamesjennison.klippercompanion.MaterialProfile
import net.jamesjennison.klippercompanion.MaterialSource
import net.jamesjennison.klippercompanion.ModelTransform

// Phase 0 (Consumer Slicer Plan §9, §16): real persistence infrastructure, built now because
// every later phase (1 through 13) depends on some piece of it - deliberately empty/unused this
// phase, since Phase 1 is what actually wires the multi-object workspace up to it. Fields below
// are the plan's §9 schema as literally as Room's flat-table model allows; `plates`,
// `targetPrinterId`, `materialAssignments`, and `settings` are Phase 1/2/3/4/9 concerns and are
// added incrementally rather than guessed at now - see ProjectObject's own comment for why a
// nullable `plateId` and `materialId` are already present even though nothing writes them yet.
@Entity(tableName = "projects")
data class Project(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
    val modifiedAt: Long,
    // Phase 2's PrinterCapabilities reference (§10) - nullable until Phase 2 exists.
    val targetPrinterId: String? = null,
    // Phase 9e: CalibrationSpec.encode() for a calibration project; null for an ordinary one.
    val calibration: String? = null,
    // Phase 10: where a downloaded model came from (designer, source, link) - MyMiniFactory's guidelines require credit.
    val attribution: String? = null,
    // Colour mixing (0.2.0, WO-30): the project's own Snapmaker Full Spectrum
    // mixed_filament_definitions string (FullSpectrumFormat.DEFINITIONS_KEY's value), null when the
    // project has no Full Spectrum mixes. Desktop keeps this in settings.overrides instead because
    // Desktop's PrepareState is itself backed by a project file it fully owns; Android's Project row
    // is that same durable store here, so it goes on the entity directly rather than through a
    // generic overrides map that doesn't exist on this side.
    val mixedFilamentDefinitions: String? = null,
    // Colour mixing (0.2.0, WO-30): the project's ColorMix virtual extruders, stored as
    // PrusaColorMixFormat's own slice-request JSON (see ColourMixPersistence.encodeColorMix) - the
    // same shape Desktop keeps in its 3MF sidecar (PrusaColorMixFormat.SIDECAR); Android has no 3MF
    // export path, so this is that sidecar's content kept in the project row instead of a file.
    val colorMixJson: String? = null,
    // The print profile chosen in Prepare: the full OrcaSlicer process preset name (ProcessPresets, the printer pack's
    // processes.json), null for the pack's default. Advanced overrides apply on top of it.
    val processPreset: String? = null,
)

// One imported model within a Project's build plate. `transform` reuses the existing
// ModelTransform (ModelTransform.kt) rather than inventing a parallel Room-only representation -
// the plan's §9 schema calls this out explicitly ("already exists as ModelTransform — promote to
// per-object"). `plateId`/`materialId` are nullable Phase 1/9 and Phase 3/8 references: real
// columns now (so Phase 1 doesn't need a schema migration just to start writing them) but unused
// until those phases exist.
@Entity(
    tableName = "project_objects",
    foreignKeys = [
        ForeignKey(
            entity = Project::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("projectId")],
)
data class ProjectObject(
    @PrimaryKey val id: String,
    val projectId: String,
    val sourceFileUri: String,
    val plateId: String? = null,
    val offsetXMm: Float = 0f,
    val offsetYMm: Float = 0f,
    val rotationZDeg: Float = 0f,
    val scale: Float = 1f,
    val materialId: String? = null,
    // Phase 3 (§11, WO-19): a denormalized snapshot of the chosen MaterialProfile's own real
    // values, written alongside materialId - not re-resolved from Spoolman at slice time, since a
    // spool a project was set up against days ago might be renamed, edited or unreachable by the
    // time it's actually sliced. materialId still identifies *which* profile was picked (so the
    // UI can show it selected again); these three columns are what slicing actually reads.
    val materialDisplayName: String? = null,
    val materialTempNozzleC: Int? = null,
    val materialTempBedC: Int? = null,
    // Phase 8 (§11, §16, WO-25): which real tool/extruder slot this object's material is
    // assigned to (a real ToolSlot.index, see ToolSlots.kt) - null means "default"/tool 0, the
    // same behavior every single-extruder project already has (materialId alone fully describes
    // slicing there; toolSlotIndex only matters once a project targets a printer whose bundled
    // profile declares more than one real extruder). Persisted per-object, not per-project,
    // because that's the whole point of this column - different objects on the same plate can
    // now genuinely target different physical tools.
    val toolSlotIndex: Int? = null,
    // Phase 9d: PaintCodec / VolumeCodec text (see ObjectExtras.kt); null = none.
    val paintJson: String? = null,
    val volumesJson: String? = null,
)

// Phase 9b: one build plate of a project. Objects with a null plateId (every project created before
// plates existed) belong to the project's first plate.
@Entity(
    tableName = "plates",
    foreignKeys = [ForeignKey(entity = Project::class, parentColumns = ["id"], childColumns = ["projectId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("projectId")],
)
data class Plate(@PrimaryKey val id: String, val projectId: String, val position: Int, val name: String)

// Room maps an entity's declared columns only, so the ModelTransform round-trip lives here as
// plain functions rather than an in-entity computed property (which Room would need an explicit
// @Ignore on to avoid trying, and failing, to persist).
fun ProjectObject.transform(): ModelTransform = ModelTransform(offsetXMm, offsetYMm, rotationZDeg, scale)

fun ProjectObject.withTransform(transform: ModelTransform): ProjectObject = copy(
    offsetXMm = transform.offsetXMm,
    offsetYMm = transform.offsetYMm,
    rotationZDeg = transform.rotationZDeg,
    scale = transform.scale,
)

// Phase 3 (§11): reconstructs the denormalized MaterialProfile snapshot, if one was ever set -
// `source` is always CUSTOM here since this is a stored snapshot, not a live Bundled/Spoolman
// lookup (a caller that needs to know the *original* source can match materialId's own prefix,
// e.g. "spoolman-"/"bundled-", the same convention toMaterialProfile()/BUNDLED_MATERIAL_PROFILES
// already use for their ids).
fun ProjectObject.material(): MaterialProfile? {
    val id = materialId ?: return null
    return MaterialProfile(
        id = id, displayName = materialDisplayName ?: id, type = materialDisplayName ?: id,
        tempNozzleC = materialTempNozzleC, tempBedC = materialTempBedC, source = MaterialSource.CUSTOM,
    )
}

fun ProjectObject.withMaterial(material: MaterialProfile?): ProjectObject = copy(
    materialId = material?.id, materialDisplayName = material?.displayName,
    materialTempNozzleC = material?.tempNozzleC, materialTempBedC = material?.tempBedC,
)

// Phase 8 (§11, §16, WO-25): real per-object tool assignment - see ProjectObject.toolSlotIndex's
// own comment. Separate from withMaterial() (material and tool slot are independent choices - a
// project could reassign which physical tool prints an object without changing what material it
// prints in, or vice versa).
fun ProjectObject.withToolSlot(index: Int?): ProjectObject = copy(toolSlotIndex = index)
