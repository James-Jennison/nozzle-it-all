package net.jamesjennison.klippercompanion

// Phase 9S: Bespok3d daemon data models (the transport and the printer-service interface both use them).

data class Bespok3dConnection(val identity: String, val token: String, val certificatePem: String)

data class Bespok3dProbe(
  val version: String,
  val license: String,
  val source: String,
  val certificatePem: String,
  val certificateSha256: String,
)

data class Bespok3dAccessRequest(
  val identity: String,
  val token: String,
  val certificatePem: String,
  val certificateSha256: String,
)

data class Bespok3dStatus(
  val version: String,
  val printerUuid: String,
)

data class Bespok3dPluginConfigField(
  val key: String,
  val label: String,
  val type: String,
  val defaultValue: String?,
  val required: Boolean,
  val options: List<String>,
  val hint: String,
  val onValue: String,
  val offValue: String,
)

data class Bespok3dPlugin(
  val id: String,
  val title: String,
  val version: String,
  val tagline: String,
  val category: String,
  val repository: String,
  val dependencies: List<String>,
  val config: List<Bespok3dPluginConfigField>,
)

data class Bespok3dPluginCatalog(
  val plugins: List<Bespok3dPlugin>,
  val installed: Map<String, String>,
)

data class Bespok3dPluginInstallResult(
  val ok: Boolean,
  val installedIds: List<String>,
  val failures: Map<String, String>,
)

data class Bespok3dHelixScreenState(
  val installed: Boolean,
  val selected: String?,
)

data class Bespok3dBundledPluginIdentity(
  val id: String,
  val version: String,
)

class Bespok3dHttpException(val statusCode: Int, message: String) : Exception(message)


interface Bespok3dReader : AutoCloseable {
    /** Read-only daemon detection; does not require an existing pairing. */
    fun bespok3dProbe(): Bespok3dProbe
    /** Returns null (not IOException) only for the daemon's explicit "pairing pending" response. */
    fun bespok3dStatus(connection: Bespok3dConnection): Bespok3dStatus?
    fun bespok3dPlugins(connection: Bespok3dConnection): Bespok3dPluginCatalog
    fun bespok3dInstallPlugins(connection: Bespok3dConnection, pluginIds: List<String>, vars: Map<String, Map<String, String>> = emptyMap()): Bespok3dPluginInstallResult
}
