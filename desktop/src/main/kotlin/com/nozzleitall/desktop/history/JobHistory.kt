package com.nozzleitall.desktop.history

import com.nozzleitall.desktop.PrinterStore
import com.nozzleitall.printer.PrinterState
import com.nozzleitall.printer.PrinterStatus
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One print that ran to its end on one of your printers. */
data class JobRecord(
    val id: String,
    val printerId: String,
    val printerName: String,
    val fileName: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    /** "finished", "cancelled" or "failed". */
    val outcome: String,
    val elapsedSeconds: Double?,
    val totalLayers: Int?,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("printerId", printerId).put("printerName", printerName).put("fileName", fileName)
        .put("startedAt", startedAtMillis).put("endedAt", endedAtMillis).put("outcome", outcome)
        .apply { elapsedSeconds?.let { put("elapsedSeconds", it) }; totalLayers?.let { put("totalLayers", it) } }

    companion object {
        fun fromJson(j: JSONObject) = JobRecord(j.getString("id"), j.getString("printerId"), j.optString("printerName"), j.getString("fileName"),
            j.optLong("startedAt"), j.optLong("endedAt"), j.optString("outcome", "finished"),
            if (j.has("elapsedSeconds")) j.getDouble("elapsedSeconds") else null, if (j.has("totalLayers")) j.getInt("totalLayers") else null)
    }
}

/**
 * Notices when a print starts and when it ends, from the status readings alone, so it works for every printer whatever its adapter.
 * A job counts only if this app saw it running; a printer that already says "finished" when the app starts is not a new result.
 * Losing the connection mid-print ends nothing: the job stays open until the printer says how it ended.
 */
class JobTracker(private val clock: () -> Long = System::currentTimeMillis) {
    private class Open(val fileName: String, val startedAt: Long, var fraction: Float, var elapsed: Double?, var layers: Int?)
    private val open = HashMap<String, Open>()
    private var counter = 0L

    fun observe(printerId: String, printerName: String, status: PrinterStatus): JobRecord? {
        val now = clock()
        val state = status.state
        val job = status.job
        if (state.isActiveJob && job != null) {
            val o = open[printerId]
            if (o == null || o.fileName != job.fileName) {
                // A different file means the last job ended without being seen; start fresh rather than merge two prints.
                open[printerId] = Open(job.fileName, now - ((job.elapsedSeconds ?: 0.0) * 1000).toLong(), job.fraction, job.elapsedSeconds, job.totalLayers)
            } else { o.fraction = job.fraction; o.elapsed = job.elapsedSeconds ?: o.elapsed; o.layers = job.totalLayers ?: o.layers }
            return null
        }
        val o = open[printerId] ?: return null
        val outcome = when (state) {
            PrinterState.FINISHED -> "finished"
            PrinterState.CANCELLED -> "cancelled"
            PrinterState.ERROR -> "failed"
            // Back to ready after printing without saying why: a job that got to the end was finished, anything shorter was stopped.
            PrinterState.READY -> if (o.fraction >= 0.99f) "finished" else "cancelled"
            else -> return null   // offline, connecting, unknown: still waiting for the printer to say
        }
        open.remove(printerId)
        return JobRecord("${now}-${counter++}", printerId, printerName, o.fileName, o.startedAt, now, outcome, o.elapsed, o.layers)
    }
}

/** The finished prints, newest first, kept in one file next to the other app data. */
class JobHistoryStore(private val file: File, private val limit: Int = 500) {
    fun load(): List<JobRecord> = runCatching {
        val a = JSONArray(file.readText()); (0 until a.length()).mapNotNull { runCatching { JobRecord.fromJson(a.getJSONObject(it)) }.getOrNull() }
    }.getOrDefault(emptyList())

    fun save(records: List<JobRecord>) {
        PrinterStore.atomicWrite(file, JSONArray(records.take(limit).map { it.toJson() }).toString(2), private = false)
    }
}

/** What to tell the user about an ended print. */
fun JobRecord.notificationTitle(): String = when (outcome) {
    "finished" -> "Print finished on $printerName"
    "cancelled" -> "Print stopped on $printerName"
    else -> "Print failed on $printerName"
}

fun JobRecord.notificationBody(): String = buildString {
    append(fileName.substringAfterLast('/'))
    elapsedSeconds?.takeIf { it > 0 }?.let { s -> val m = (s / 60).toLong(); append(" · "); append(if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min") }
}

/** Shows a message outside the window: the system tray where the desktop has one, `notify-send` on Linux otherwise. */
object DesktopNotifier {
    fun notify(title: String, body: String) {
        runCatching {
            if (System.getProperty("os.name").contains("Linux", ignoreCase = true)) {
                ProcessBuilder("notify-send", "--app-name=Nozzle It All", title, body).redirectErrorStream(true).start()
                return
            }
            if (java.awt.SystemTray.isSupported()) {
                val tray = java.awt.SystemTray.getSystemTray()
                val icon = java.awt.TrayIcon(java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB), "Nozzle It All")
                tray.add(icon); icon.displayMessage(title, body, java.awt.TrayIcon.MessageType.INFO)
                Thread { Thread.sleep(8000); tray.remove(icon) }.apply { isDaemon = true; start() }
            }
        }
    }
}
