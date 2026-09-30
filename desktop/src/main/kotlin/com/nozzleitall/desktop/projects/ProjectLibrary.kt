package com.nozzleitall.desktop.projects

import com.nozzleitall.desktop.AppPaths
import com.nozzleitall.desktop.prepare.MeshIO
import com.nozzleitall.project.*
import java.io.File
import java.util.UUID

data class ProjectSummary(val file: File, val name: String, val projectId: String?, val modifiedMillis: Long, val objectCount: Int, val printerModel: String?,
                          val problem: String? = null)

/**
 * The user's projects: one canonical 3MF (with the Nozzle manifest inside) per project in the projects folder.
 * Deleting moves a project to a trash folder instead of erasing it.
 */
class ProjectLibrary(private val paths: AppPaths) {
    val dir get() = paths.projects
    private val trash get() = File(paths.projects, ".trash")

    fun list(): List<ProjectSummary> = (dir.listFiles { f -> f.isFile && f.name.endsWith(".3mf", true) } ?: emptyArray()).map { f ->
        try {
            val p = ThreeMf.read(f, ReadLimits(maxTriangles = 50_000_000))
            ProjectSummary(f, p.manifest?.name ?: p.metadata["Title"] ?: f.nameWithoutExtension, p.manifest?.projectId, f.lastModified(), p.objects.size,
                p.manifest?.printer?.model, p.manifestProblem)
        } catch (e: Exception) {
            ProjectSummary(f, f.nameWithoutExtension, null, f.lastModified(), 0, null, e.message ?: "This project can't be opened.")
        }
    }.sortedByDescending { it.modifiedMillis }

    fun fileFor(name: String): File {
        val base = name.replace(Regex("[^A-Za-z0-9 ._()-]"), "_").trim().ifBlank { "Untitled" }.take(80)
        var f = File(dir, "$base.3mf"); var n = 2
        while (f.exists()) { f = File(dir, "$base ($n).3mf"); n++ }
        return f
    }

    fun newManifest(name: String, version: String) = ProjectManifest(UUID.randomUUID().toString(), 1, name,
        ProjectManifest.Producer("Nozzle It All", "desktop", version), ProjectManifest.Producer("Nozzle It All", "desktop", version), System.currentTimeMillis())

    /** Brings any STL/OBJ/3MF into the library as a new Nozzle project. The original file is never modified. */
    fun import(source: File, version: String): File {
        val target = fileFor(source.nameWithoutExtension)
        val project = if (source.name.endsWith(".3mf", true)) {
            val p = ThreeMf.read(source)
            p.copy(manifest = p.manifest ?: newManifest(source.nameWithoutExtension, version).copy(plates = listOf(ProjectManifest.PlateEntry(1, "Plate 1",
                p.objects.map { ProjectManifest.ObjectEntry(it.id, it.name, 1) }))))
        } else {
            val mesh = MeshIO.read(source)
            Project3mf(listOf(ModelObject(1, source.nameWithoutExtension, mesh)), mapOf("Title" to source.nameWithoutExtension),
                newManifest(source.nameWithoutExtension, version).copy(plates = listOf(ProjectManifest.PlateEntry(1, "Plate 1", listOf(ProjectManifest.ObjectEntry(1, source.nameWithoutExtension, 1))))))
        }
        ThreeMf.writeAtomically(project, target)
        return target
    }

    fun save(project: Project3mf, file: File) = ThreeMf.writeAtomically(project, file)

    fun moveToTrash(file: File): File {
        trash.mkdirs()
        val dest = File(trash, "${System.currentTimeMillis()}-${file.name}")
        check(file.renameTo(dest)) { "Couldn't move the project to the trash." }
        return dest
    }

    fun restore(trashed: File): File { val dest = fileFor(trashed.name.substringAfter('-').removeSuffix(".3mf")); check(trashed.renameTo(dest)); return dest }
}
