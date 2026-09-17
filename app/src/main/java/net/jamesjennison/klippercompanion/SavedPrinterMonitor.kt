package net.jamesjennison.klippercompanion

import kotlinx.coroutines.*

data class PrinterConnection(val connected: Boolean = false, val state: String = "Connecting…", val snapshot: PrinterSnapshot? = null, val cameras: List<Camera> = emptyList())

/** Read-only sessions for saved printers other than the selected control target. */
internal class SavedPrinterMonitor(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val factory: (String) -> PrinterService,
    private val changed: (Map<String, PrinterConnection>) -> Unit
) {
    private class Session(var service: PrinterService? = null, var job: Job? = null)
    private val sessions = mutableMapOf<String, Session>()
    private val states = mutableMapOf<String, PrinterConnection>()

    fun reconcile(addresses: Set<String>) {
        (sessions.keys - addresses).forEach(::remove)
        addresses.filter { it !in sessions }.forEach { address ->
            val session = Session()
            sessions[address] = session
            states[address] = PrinterConnection()
            session.job = scope.launch {
                var cameras = emptyList<Camera>()
                var cameraReadAt: Long? = null
                while (isActive) {
                    val status = try {
                        val service = session.service ?: factory(address).also { session.service = it }
                        val snapshot = withContext(io) { service.snapshot() }
                        val now = System.nanoTime()
                        if(cameraReadAt == null || now - cameraReadAt!! >= 30_000_000_000L) {
                            cameras = withContext(io) { runCatching { service.cameras() }.getOrDefault(emptyList()) }
                            cameraReadAt = now
                        }
                        PrinterConnection(true, if (snapshot.ready) snapshot.state else "Klipper ${snapshot.state}", snapshot, cameras)
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { PrinterConnection(false, "Unavailable • retrying while open") }
                    ensureActive()
                    if (sessions[address] !== session) return@launch
                    states[address] = status
                    changed(states.toMap())
                    delay(2_000)
                }
            }
        }
        changed(states.toMap())
    }

    fun remove(address: String) {
        val session = sessions.remove(address)
        session?.job?.cancel()
        // One faulty client must not prevent the remaining sessions from stopping.
        try { session?.service?.close() } catch (_: Exception) { }
        states.remove(address)
        changed(states.toMap())
    }

    fun stop() = reconcile(emptySet())
}
