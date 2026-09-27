package com.nozzleitall.desktop.workspace

import com.nozzleitall.desktop.AppPaths
import com.nozzleitall.project.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * The bridge between Nozzle and the Advanced Workspace (the Orca-derived specialist editor), protocol nozzle-workspace
 * 1.x (docs/protocols/WORKSPACE_BRIDGE.md).
 *
 * A session is a folder holding session.json and a working copy of the project. The workspace runs as its own process
 * with Nozzle's workspace data folder; it edits the working copy and may update session.json. Nozzle takes the result
 * back only after checking it: the archive must be complete, belong to the same project, and not conflict with edits
 * made in Nozzle meanwhile. The user's saved project is never touched until then.
 */
class AdvancedWorkspace(private val paths: AppPaths) {
    companion object {
        const val PROTOCOL = "nozzle-workspace"
        const val MAJOR = 1
        const val MINOR = 0

        fun locate(env: Map<String, String> = System.getenv()): File? = com.nozzleitall.desktop.prepare.SliceEngine.locate(env)
    }

    data class Session(val dir: File, val id: String, val projectId: String, val baseRevision: Long, val originalFile: File, val workingCopy: File, val baseSha256: String) {
        val sessionFile get() = File(dir, "session.json")
    }

    sealed class ReturnResult {
        /** The workspace's version, ready to become the project. [restoredManifest] is true when Nozzle had to put its own details back. */
        data class Changed(val project: Project3mf, val restoredManifest: Boolean) : ReturnResult()
        object Unchanged : ReturnResult()
        data class Conflict(val workspaceVersion: Project3mf, val message: String) : ReturnResult()
        data class Refused(val message: String) : ReturnResult()
    }

    var running: Process? = null; private set

    fun begin(projectFile: File): Session {
        val project = ThreeMf.read(projectFile)
        val manifest = project.manifest ?: throw ProjectFormatException("Save the project in Nozzle It All before opening it in the Advanced Workspace.")
        val id = UUID.randomUUID().toString()
        val dir = File(paths.workspaceSessions, id).apply { mkdirs() }
        val working = File(dir, projectFile.name)
        projectFile.copyTo(working)
        val session = Session(dir, id, manifest.projectId, manifest.revision, projectFile, working, ThreeMf.sha256(working.readBytes()))
        writeSession(session, "open")
        return session
    }

    fun writeSession(s: Session, state: String, extra: JSONObject.() -> Unit = {}) {
        val o = runCatching { JSONObject(s.sessionFile.readText()) }.getOrDefault(JSONObject())
        o.put("protocol", PROTOCOL).put("version", JSONArray().put(MAJOR).put(MINOR)).put("sessionId", s.id).put("projectId", s.projectId)
            .put("baseRevision", s.baseRevision).put("workingCopy", s.workingCopy.name).put("baseSha256", s.baseSha256).put("state", state)
            .put("updatedAtMillis", System.currentTimeMillis()).apply(extra)
        com.nozzleitall.desktop.PrinterStore.atomicWrite(s.sessionFile, o.toString(2), private = false)
    }

    /** Launches the workspace on the session's working copy. Its data folder is Nozzle's own, never Orca's or Snapmaker Orca's. */
    fun launch(s: Session, binary: File): Process {
        check(running?.isAlive != true) { "The Advanced Workspace is already open." }
        val pb = ProcessBuilder(binary.absolutePath, "--datadir", paths.workspaceProfile.absolutePath, s.workingCopy.absolutePath)
            .directory(s.dir).redirectErrorStream(true).redirectOutput(File(s.dir, "workspace.log"))
        pb.environment()["NOZZLE_WORKSPACE_SESSION"] = s.sessionFile.absolutePath
        return pb.start().also { running = it; writeSession(s, "running") { put("pid", it.pid()) } }
    }

    /** Checks the session file the workspace may have updated. A different major version is refused. */
    fun checkProtocol(s: Session): String? {
        val o = runCatching { JSONObject(s.sessionFile.readText()) }.getOrElse { return "The session record is damaged." }
        if (o.optString("protocol") != PROTOCOL) return "The session record isn't a Nozzle It All workspace session."
        val major = o.optJSONArray("version")?.optInt(0, -1) ?: -1
        return if (major != MAJOR) "The Advanced Workspace speaks session format $major; this Nozzle It All speaks $MAJOR. Install matching versions." else null
    }

    /**
     * Brings the workspace's result back. [currentRevision] is the project's revision in Nozzle now, so edits made in both
     * places are detected rather than silently overwritten.
     */
    fun collect(s: Session, currentRevision: Long, nozzleCopy: Project3mf): ReturnResult {
        checkProtocol(s)?.let { return ReturnResult.Refused(it) }
        if (!s.workingCopy.exists()) return ReturnResult.Refused("The Advanced Workspace didn't leave a project to bring back. Your project is unchanged.")
        val bytes = s.workingCopy.readBytes()
        if (ThreeMf.sha256(bytes) == s.baseSha256) return ReturnResult.Unchanged
        val back = try { ThreeMf.read(s.workingCopy) }
            catch (e: IncompatibleProjectException) { return ReturnResult.Refused(e.message ?: "The project was saved in a newer format.") }
            catch (e: ProjectFormatException) { return ReturnResult.Refused("The Advanced Workspace's save is incomplete or damaged (${e.message}). Your project is unchanged; the workspace copy is kept in ${s.dir.absolutePath}.") }
        if (back.objects.isEmpty()) return ReturnResult.Refused("The Advanced Workspace saved a project with no objects. Your project is unchanged.")
        val owner = back.manifest?.projectId ?: back.metadata[ProjectManifest.META_PROJECT_ID]
        if (owner != null && owner != s.projectId) return ReturnResult.Refused("That file belongs to a different project. Your project is unchanged.")
        // If the workspace dropped Nozzle's details, put Nozzle's own copy back; materials are re-checked by the user.
        val restored = back.manifest == null
        val manifest = (back.manifest ?: nozzleCopy.manifest ?: return ReturnResult.Refused("Nozzle's project details are missing.")).let { m ->
            // Keep materials/slots for objects that still exist; new objects from the workspace get slot 1.
            val known = m.plates.flatMap { it.objects }.associateBy { it.objectId }
            m.copy(revision = maxOf(m.revision, currentRevision) + 1, modifiedAtMillis = System.currentTimeMillis(),
                modifiedBy = ProjectManifest.Producer("Nozzle It All Advanced Workspace", "desktop", m.modifiedBy.version),
                plates = listOf(ProjectManifest.PlateEntry(1, m.plates.firstOrNull()?.name ?: "Plate 1",
                    back.objects.map { o -> known[o.id]?.copy(name = o.name) ?: ProjectManifest.ObjectEntry(o.id, o.name, 1) }, m.plates.firstOrNull()?.unknown ?: JSONObject())))
        }
        val result = back.copy(manifest = manifest)
        if (currentRevision != s.baseRevision)
            return ReturnResult.Conflict(result, "This project also changed in Nozzle while it was open in the Advanced Workspace. Keep both, or choose one.")
        return ReturnResult.Changed(result, restored)
    }

    fun finish(s: Session, outcome: String) { writeSession(s, outcome) }
}
