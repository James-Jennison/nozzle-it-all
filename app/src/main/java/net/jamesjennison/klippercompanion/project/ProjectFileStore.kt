package net.jamesjennison.klippercompanion.project

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

// Phase 1 (Consumer Slicer Plan §16): a project's imported model files need to survive process
// death and app restarts reliably - unlike SliceAndPrintPanel's existing single-share-intent flow
// (copySharedModel in SliceAndPrintPanel.kt), which deliberately uses cacheDir since it only ever
// needs to survive one slicing session. A saved project is exactly the "drafts" case Phase 1's
// scope calls out, so its model files live under filesDir instead, in their own
// per-project/per-object directory (not shared with cacheDir's own slice-inputs/ at all).
object ProjectFileStore {
    private fun projectDir(context: Context, projectId: String): File {
        val dir = File(File(context.filesDir, "projects"), projectId)
        check(dir.isDirectory || dir.mkdirs()) { "Cannot create local storage for this project." }
        return dir
    }

    // Copies a shared/picked model file (content:// or file:// Uri) into this project's own
    // persisted storage, named by the new object's own id (not the original filename) so two
    // objects imported from files that happen to share a name never collide - the original
    // filename is kept only as a display label, resolved separately (see displayName below).
    fun importObject(context: Context, projectId: String, objectId: String, source: Uri, extension: String): File {
        val target = File(projectDir(context, projectId), "$objectId.$extension")
        val input = context.contentResolver.openInputStream(source)
            ?: throw IllegalArgumentException("Cannot open the selected file.")
        input.use { stream -> target.outputStream().use { stream.copyTo(it) } }
        return target
    }

    /** A fresh, uniquely named model file inside the project's own storage (for edited/cut meshes). */
    fun newModelFile(context: Context, projectId: String, extension: String): File =
        File(projectDir(context, projectId), "${java.util.UUID.randomUUID()}.$extension")

    fun deleteObject(context: Context, projectId: String, objectId: String) {
        projectDir(context, projectId).listFiles { f -> f.nameWithoutExtension == objectId }?.forEach { it.delete() }
    }

    // Deletes model files no object references any more. Duplicated objects share one file, so
    // references (not object ids) decide what is safe to delete.
    fun pruneUnreferenced(context: Context, projectId: String, referencedFileUris: Set<String>) {
        val referenced = referencedFileUris.mapNotNull { Uri.parse(it).path?.let(::File)?.name }.toSet()
        projectDir(context, projectId).listFiles()?.filter { it.isFile && it.name !in referenced }?.forEach { it.delete() }
    }

    fun deleteProject(context: Context, projectId: String) {
        projectDir(context, projectId).deleteRecursively()
    }

    // Real, not guessed: reads the picker/share Uri's own display name via ContentResolver where
    // available, the same query SliceAndPrintPanel.kt's existing single-object flow already uses.
    fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()
}
