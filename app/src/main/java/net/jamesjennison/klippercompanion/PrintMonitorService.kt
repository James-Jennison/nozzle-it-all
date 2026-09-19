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
            (services.keys - addresses).forEach { address -> services.remove(address)?.let { runCatching { it.close() } }; previous.remove(address) }
            for (profile in profiles) {
                val current = poll(profile)
                PrintAlerts.detect(profile.address, profile.label, previous[profile.address], current).forEach(::notifyAlert)
                previous[profile.address] = current
            }
            updateStatusNotification(profiles, previous)
            delay(POLL_INTERVAL_MS)
        }
    }
    private fun poll(profile: PrinterProfile): PrinterConnection = try {
        val service = services.getOrPut(profile.address) { Moonraker(profile.address, profile.apiKey) }
        val snapshot = service.snapshot()
        PrinterConnection(true, if (snapshot.ready) snapshot.state else "not ready", snapshot)
    } catch (e: CancellationException) { throw e } catch (_: Exception) {
        services.remove(profile.address)?.let { runCatching { it.close() } }
        PrinterConnection(false, "Unavailable", null)
    }
    private fun notifyAlert(alert: PrintAlert) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val notification = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Nozzle It All").setContentText(alert.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT).setAutoCancel(true).build()
        manager.notify(nextNotificationId++, notification)
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
