package net.jamesjennison.klippercompanion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.*

/** Opt-in background monitoring (P17). Polls every saved printer on a fixed interval and turns
 * PrintAlerts.detect() output into notifications; the persistent low-priority notification this
 * foreground service must show is what tells the owner this is still running. Started/stopped
 * only from the "Background print alerts" toggle in MainActivity - never launches on its own. */
class PrintMonitorService : Service() {
    companion object {
        const val CHANNEL_ALERTS = "print_alerts"
        const val CHANNEL_MONITOR = "print_monitor_status"
        private const val FOREGROUND_ID = 1
        private const val POLL_INTERVAL_MS = 20_000L
        fun start(context: Context) = context.startForegroundService(Intent(context, PrintMonitorService::class.java))
        fun stop(context: Context) = context.stopService(Intent(context, PrintMonitorService::class.java))
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val services = mutableMapOf<String, PrinterService>()
    private val previous = mutableMapOf<String, PrinterConnection>()
    private val consecutiveFailures = mutableMapOf<String, Int>()
    private var job: Job? = null
    private var nextNotificationId = 2

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForeground(FOREGROUND_ID, statusNotification("Starting…"))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (job?.isActive != true) job = scope.launch { monitorLoop() }
        return START_STICKY
    }
    override fun onDestroy() {
        // Explicit rather than relying on stopService()'s implicit cleanup of a startForeground()
        // notification: observed at least one device where a stale notification survived after
        // the hosting process was killed, so don't assume the framework default is reliable here.
        stopForeground(STOP_FOREGROUND_REMOVE)
        job?.cancel()
        services.values.forEach { runCatching { it.close() } }
        services.clear()
        scope.cancel()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun monitorLoop() {
        val prefs = getSharedPreferences("printer", 0)
        val secrets = CredentialStore.open(applicationContext)
        while (true) {
            val profiles = try { PrinterPreferences.profiles(prefs, secrets) } catch (_: Exception) { emptyList() }
            // Deliberately does NOT stopSelf() on an empty list: there's no signal from the app
            // process back to an already-running service telling it a printer was just added, so
            // stopping here would mean "enable alerts, then add your first printer" silently never
            // starts monitoring until the toggle is manually cycled off and on again. Idling costs
            // one empty poll tick every POLL_INTERVAL_MS, not worth that footgun to avoid.
            val addresses = profiles.map { it.address }.toSet()
            (services.keys - addresses).forEach { address -> services.remove(address)?.let { runCatching { it.close() } }; previous.remove(address); consecutiveFailures.remove(address) }
            for (profile in profiles) {
                val raw = poll(profile)
                val failures = if (raw.connected) 0 else (consecutiveFailures[profile.address] ?: 0) + 1
                consecutiveFailures[profile.address] = failures
                // A real, repeated report: a Snapmaker U1/PAXX dropping WiFi briefly and
                // reassociating on its own within a poll cycle or two must not page the owner
                // every single time - see ConnectionDebounce's own doc comment.
                val current = ConnectionDebounce.debounce(raw, previous[profile.address], failures)
                PrintAlerts.detect(profile.address, profile.label, previous[profile.address], current, extended = getSharedPreferences("alerts", 0).getBoolean("extended", false)).forEach(::notifyAlert)
                previous[profile.address] = current
            }
            updateStatusNotification(profiles, previous)
            // Piggybacks on work this loop is already doing rather than adding a second poller
            // or a WorkManager dependency - only relevant while this service happens to be
            // running; otherwise the widget falls back to its own OS-throttled updatePeriodMillis
            // and manual tap-to-refresh (see nozzle_widget_info.xml).
            runCatching { NozzlePrinterWidget().updateAll(applicationContext) }
            delay(POLL_INTERVAL_MS)
        }
    }
    private fun poll(profile: PrinterProfile): PrinterConnection = try {
        // Routed by kind, like the foreground loop: a Bambu profile's bare host is not an address
        // Moonraker's own constructor accepts, and would otherwise poll as permanently offline.
        val service = services.getOrPut(profile.address) { printerServiceFor(profile, profile.address) }
        val snapshot = service.snapshot()
        PrinterConnection(true, if (snapshot.ready) snapshot.state else "not ready", snapshot)
    } catch (e: CancellationException) { throw e } catch (_: Exception) {
        services.remove(profile.address)?.let { runCatching { it.close() } }
        PrinterConnection(false, "Unavailable", null)
    }
    private fun notifyAlert(alert: PrintAlert) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val builder = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Nozzle It All").setContentText(alert.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT).setAutoCancel(true)
            .setContentIntent(openAppIntent())
        // Contextual, not universal: only kinds where the printer is actually in a state a
        // command could apply to get an action at all. COMPLETED/CANCELLED/OFFLINE/BACK_ONLINE
        // have nothing live to act on - tapping the notification itself already opens the app.
        // Tapping an action only stages the command; it is never sent from here (see
        // MainActivity's stagedAddress/stagedAction handling) - matches this app's own rule that
        // every mutating command goes through an explicit review/confirm step, never fires from
        // a background receiver.
        when (alert.kind) {
            AlertKind.PAUSED -> { builder.addAction(0, "Resume", stageActionIntent(alert.address, "resume")); builder.addAction(0, "Cancel", stageActionIntent(alert.address, "cancel")) }
            AlertKind.ERROR -> builder.addAction(0, "Cancel", stageActionIntent(alert.address, "cancel"))
            else -> {}
        }
        manager.notify(nextNotificationId++, builder.build())
    }
    private fun stageActionIntent(address: String, action: String): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_STAGE_ADDRESS, address)
            putExtra(MainActivity.EXTRA_STAGE_ACTION, action)
        }
        // Distinct per (address, action): FLAG_UPDATE_CURRENT reuses a cached PendingIntent's
        // extras by request code alone, so two different printers'/actions' notifications must
        // not share one or a later tap could stage the wrong command.
        val requestCode = (address + action).hashCode()
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getActivity(this, requestCode, intent, flags)
    }
    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS, "Print alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Completion, error and connectivity alerts for your saved printers."
        })
        manager.createNotificationChannel(NotificationChannel(CHANNEL_MONITOR, "Background monitoring status", NotificationManager.IMPORTANCE_MIN).apply {
            description = "The persistent notification that background print monitoring is active."
        })
    }
    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getActivity(this, 0, intent, flags)
    }
    private fun statusNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_MONITOR).setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("Monitoring your printers").setContentText(text).setContentIntent(openAppIntent())
            .setOngoing(true).setPriority(NotificationCompat.PRIORITY_MIN).build()
    private fun updateStatusNotification(profiles: List<PrinterProfile>, states: Map<String, PrinterConnection>) {
        val text = if (profiles.isEmpty()) "No saved printers yet" else {
            val online = profiles.count { states[it.address]?.connected == true }
            "$online of ${profiles.size} printer${if (profiles.size == 1) "" else "s"} reachable"
        }
        getSystemService(NotificationManager::class.java)?.notify(FOREGROUND_ID, statusNotification(text))
    }
}
