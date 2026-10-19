package net.jamesjennison.klippercompanion.environment

import android.content.Context
import com.nozzleitall.environment.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.jamesjennison.klippercompanion.CredentialStore
import java.util.concurrent.atomic.AtomicLong

/** No startup IO. Key is never part of observable state, logs, exports or saved UI state. */
class AndroidGoveeNames(private val context: Context) {
    private val epoch = AtomicLong()
    private val storageLock = Any()
    private val names = MutableStateFlow<List<GoveeDeviceName>>(emptyList())
    val devices = names.asStateFlow()
    private fun credentials() = CredentialStore.open(context, "govee-name-credentials")
    fun hasSavedKey(): Boolean = credentials().contains("api-key")
    @Synchronized fun begin(): Long = epoch.incrementAndGet()
    @Synchronized fun cancel() { epoch.incrementAndGet() }
    fun import(key: String?, remember: Boolean, request: Long): Int {
        val selected = key ?: credentials().getString("api-key", null) ?: throw GoveeNameException(GoveeNameFailure.KEY)
        val result = GoveeNameApi().fetch(selected) { epoch.get() == request }
        synchronized(storageLock) {
            if (epoch.get() != request) throw GoveeNameException(GoveeNameFailure.CANCELLED)
            if (key != null && remember) check(credentials().edit().putString("api-key", selected).commit())
        }
        synchronized(this) {
            if (epoch.get() != request) throw GoveeNameException(GoveeNameFailure.CANCELLED)
            names.value = result
        }
        return result.size
    }
    fun forget() {
        synchronized(this) { cancel(); names.value = emptyList() }
        synchronized(storageLock) { check(credentials().edit().remove("api-key").commit()) }
    }
}
