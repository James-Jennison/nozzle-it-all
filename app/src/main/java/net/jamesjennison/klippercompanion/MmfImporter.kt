package net.jamesjennison.klippercompanion

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.jamesjennison.klippercompanion.project.ProjectDao
import net.jamesjennison.klippercompanion.project.ProjectViewModel
import java.io.File
import java.util.UUID

/** Turns a MyMiniFactory model into a saved project: downloads the chosen files, unpacks archives, adds every model, and records the credit line. */
class MmfImporter(private val api: MmfApi, private val context: Context, private val dao: ProjectDao) {
    /** @return the new project's id. Throws [MmfException] (or IllegalStateException when nothing printable was found). */
    suspend fun import(obj: MmfObject, files: List<MmfFile>, accessToken: String, onStatus: (String) -> Unit = {}): String = withContext(Dispatchers.IO) {
        val work = File(context.cacheDir, "mmf-${UUID.randomUUID()}").also { it.mkdirs() }
        try {
            val models = ArrayList<File>()
            for ((i, f) in files.filter { it.isModel || it.isArchive }.withIndex()) {
                onStatus("Downloading ${f.filename} (${i + 1}/${files.count { it.isModel || it.isArchive }})…")
                val safe = f.filename.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100).ifBlank { "file-${f.id}" }
                val dest = File(work, "${f.id}-$safe")
                api.download(f, accessToken, dest)
                if (f.isArchive) { onStatus("Unpacking ${f.filename}…"); models += MmfArchive.extractModels(dest, File(work, "unzipped-${f.id}")); dest.delete() } else models += dest
            }
            check(models.isNotEmpty()) { "That model has no STL, 3MF or OBJ files to print." }
            onStatus("Creating project…")
            val vm = ProjectViewModel(context.applicationContext, dao)
            val project = vm.newProject(obj.name.take(80))
            for (m in models) vm.addObject(Uri.fromFile(m))
            vm.setAttribution(obj.attribution())
            project.id
        } finally { work.deleteRecursively() }
    }
}
