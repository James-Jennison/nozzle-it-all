package net.jamesjennison.klippercompanion

/** Finds a finished job's G-code among the printer's current files, so History can offer "Reprint" only when the file is still there. */
object Reprint {
    fun resolve(jobFilename: String, printerFiles: List<String>): String? {
        if (jobFilename.isBlank()) return null
        printerFiles.firstOrNull { it == jobFilename }?.let { return it }
        val base = jobFilename.substringAfterLast('/')
        // The printer may report the job with or without its folder; a single unambiguous match by name is safe, several are not.
        return printerFiles.filter { it.substringAfterLast('/') == base }.singleOrNull()
    }
}
