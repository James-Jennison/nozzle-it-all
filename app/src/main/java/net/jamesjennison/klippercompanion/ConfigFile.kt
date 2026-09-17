package net.jamesjennison.klippercompanion

data class ConfigFileContent(val filename: String, val userSection: String, val autoSection: String) {
    val hasAutoSection: Boolean get() = autoSection.isNotBlank()
}
interface ConfigFileReader : AutoCloseable {
    fun configFile(): ConfigFileContent
}
object ConfigFile {
    private val marker = Regex("""^#\*#\s*<-+\s*SAVE_CONFIG\s*-+>\s*$""", RegexOption.MULTILINE)
    const val MAX_BYTES = 1_000_000
    fun split(filename: String, raw: String): ConfigFileContent {
        require(raw.length <= MAX_BYTES) { "Config file exceeds the supported size." }
        val match = marker.find(raw)
        return if (match == null) ConfigFileContent(filename, raw, "")
        else ConfigFileContent(filename, raw.substring(0, match.range.first).trimEnd('\n'), raw.substring(match.range.first))
    }
}
