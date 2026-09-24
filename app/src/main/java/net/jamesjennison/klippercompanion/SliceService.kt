package net.jamesjennison.klippercompanion

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

/** Keeps the process alive and shows progress while an on-device slice runs, so backgrounding the app
 * mid-slice doesn't get it killed. Started/stopped by SlicingCoordinator around each slice only. */
class SliceService : Service() {
    companion object {
        private const val CHANNEL = "slicing_progress"
        private const val ID = 3
        // Android kills the process if a startForegroundService() is stopped before the service has called
        // startForeground(), which a fast slice would otherwise do - so stop() waits for onCreate() first.
        @Volatile private var foregrounded: CompletableDeferred<Unit>? = null
        fun start(context: Context) {
            val pending = CompletableDeferred<Unit>()
            foregrounded = pending
            if (runCatching { context.startForegroundService(Intent(context, SliceService::class.java)) }.isFailure) pending.complete(Unit)
        }
        suspend fun stop(context: Context) = withContext(NonCancellable) {
            withTimeoutOrNull(10_000) { foregrounded?.await() }
            context.stopService(Intent(context, SliceService::class.java))
            Unit
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun notification(percent: Int) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("Slicing on this device")
        .setContentText("$percent%").setProgress(100, percent, false).setOngoing(true).setOnlyAlertOnce(true).build()

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Slicing progress", NotificationManager.IMPORTANCE_LOW))
        startForeground(ID, notification(0))
        foregrounded?.complete(Unit)
        scope.launch {
            val manager = getSystemService(NotificationManager::class.java)
            while (isActive) { delay(1000); manager.notify(ID, notification(SlicingCoordinator.progress().coerceIn(0, 100))) }
        }
    }
    // Every startForegroundService() must be answered by startForeground(), also when the service is already alive, and start()'s
    // deferred is completed here so stop() never waits out its timeout for a second slice.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(ID, notification(SlicingCoordinator.progress().coerceIn(0, 100)))
        foregrounded?.complete(Unit)
        return START_NOT_STICKY
    }
    override fun onDestroy() { scope.cancel(); stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
