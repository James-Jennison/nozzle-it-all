package net.jamesjennison.klippercompanion

data class ConfigFileContent(val filename: String, val userSection: String, val autoSection: String) {
    val hasAutoSection: Boolean get() = autoSection.isNotBlank()
}
interface ConfigFileReader : AutoCloseable {
    fun configFile(): ConfigFileContent
}
/** Writes config to disk. Separate from [ConfigFileReader] since it needs the file
 * (config) root's copy/upload endpoints, not the gcode command path other controls use. */
interface ConfigWriter : AutoCloseable {
    fun backupConfig(filename: String): String
    fun writeConfig(filename: String, content: String)
}
object ConfigFile {
    private val marker = Regex("""^#\*#\s*<-+\s*SAVE_CONFIG\s*-+>\s*$""", RegexOption.MULTILINE)
    const val MAX_BYTES = 1_000_000
    // Editing and restarting mid-print would abort the job; error is included since fixing
    // a broken config is exactly the recovery path a printer stuck in error needs (matches
    // MacroTools.allowedStates, not the narrower heater/fan/speed-flow idle set).
    val allowedStates = setOf("standby", "complete", "cancelled", "error")
    fun split(filename: String, raw: String): ConfigFileContent {
        require(raw.length <= MAX_BYTES) { "Config file exceeds the supported size." }
        val match = marker.find(raw)
        return if (match == null) ConfigFileContent(filename, raw, "")
        else ConfigFileContent(filename, raw.substring(0, match.range.first).trimEnd('\n'), raw.substring(match.range.first))
    }
    fun validateUserSection(text: String): String {
        require(text.length <= MAX_BYTES) { "Configuration is too large." }
        require(text.none { it.isISOControl() && it != '\n' && it != '\t' }) { "Remove unsupported control characters." }
        require(!marker.containsMatchIn(text)) { "Do not include the SAVE_CONFIG marker; it is managed automatically." }
        return text
    }
    /** Reassembles the full file from a validated, freshly-edited user section and the
     * auto-generated section as most recently read from the printer (never user-edited). */
    fun assemble(userSection: String, autoSection: String): String {
        val body = validateUserSection(userSection).trimEnd('\n')
        val full = if (autoSection.isBlank()) body else "$body\n$autoSection"
        require(full.length <= MAX_BYTES) { "Configuration is too large." }
        return full
    }
}
