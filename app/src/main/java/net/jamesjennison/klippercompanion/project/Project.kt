package net.jamesjennison.klippercompanion.project

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
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
)

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
