package net.jamesjennison.klippercompanion.environment

import android.content.Context
import com.nozzleitall.environment.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.jamesjennison.klippercompanion.CredentialStore
import java.util.concurrent.atomic.AtomicLong

/** No startup IO. Key is never part of observable state, logs, exports or saved UI state. */
class AndroidGoveeNames internal constructor(private val credentials: () -> android.content.SharedPreferences,
    private val fetch: (String, () -> Boolean) -> List<GoveeDeviceName>) {
    constructor(context: Context) : this({ CredentialStore.open(context, "govee-name-credentials") }, { key, current -> GoveeNameApi().fetch(key, current) })
    private val epoch = AtomicLong()
    private val storageLock = Any()
    private val credentialStore by lazy { credentials() }
    private val names = MutableStateFlow<List<GoveeDeviceName>>(emptyList())
    val devices = names.asStateFlow()
    fun hasSavedKey(): Boolean = synchronized(storageLock) { credentialStore.contains("api-key") }
    @Synchronized fun begin(): Long = epoch.incrementAndGet()
    @Synchronized fun cancel() { epoch.incrementAndGet() }
    fun import(key: String?, remember: Boolean, request: Long): Int {
        if (epoch.get() != request) throw GoveeNameException(GoveeNameFailure.CANCELLED)
        val selected = key ?: synchronized(storageLock) { credentialStore.getString("api-key", null) } ?: throw GoveeNameException(GoveeNameFailure.KEY)
        val result = fetch(selected) { epoch.get() == request }
        synchronized(storageLock) {
            if (epoch.get() != request) throw GoveeNameException(GoveeNameFailure.CANCELLED)
            val persist = key != null && remember
            val previous = if (persist) credentialStore.getString("api-key", null) else null
            try {
                if (persist && !credentialStore.edit().putString("api-key", selected).commit()) throw GoveeNameException(GoveeNameFailure.STORAGE)
                // Only publication takes the short Main-visible lock, never disk IO.
                synchronized(this) {
                    if (epoch.get() != request) throw GoveeNameException(GoveeNameFailure.CANCELLED)
                    names.value = result
                }
            } catch (e: Exception) {
                // A cancellation racing a commit restores the previous credential, not merely its absence.
                if (persist) {
                    val restore = credentialStore.edit()
                    if (previous == null) restore.remove("api-key") else restore.putString("api-key", previous)
                    if (!restore.commit()) throw GoveeNameException(GoveeNameFailure.STORAGE)
                }
                throw e
            }
        }
        return result.size
    }
    fun forget() {
        synchronized(this) { cancel(); names.value = emptyList() }
        synchronized(storageLock) { check(credentialStore.edit().remove("api-key").commit()) }
    }
}
