package net.jamesjennison.klippercompanion.environment

import android.content.Context
import com.nozzleitall.environment.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.jamesjennison.klippercompanion.CredentialStore
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*

/** No startup IO. Key is never part of observable state, logs, exports or saved UI state. */
class AndroidGoveeNames internal constructor(private val credentials: () -> android.content.SharedPreferences,
    private val fetch: (String, () -> Boolean) -> List<GoveeDeviceName>) {
    constructor(context: Context) : this({ CredentialStore.open(context, "govee-name-credentials") }, { key, current -> GoveeNameApi().fetch(key, current) })
    private val epoch = AtomicLong()
    private val storageLock = Any()
    private val credentialStore by lazy { credentials() }
    private val names = MutableStateFlow<List<GoveeDeviceName>>(emptyList())
    val devices = names.asStateFlow()
    private var pendingForgets = 0
    private val cleanup = MutableStateFlow(false)
    val forgetting = cleanup.asStateFlow()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val savedKeyRevoked = java.util.concurrent.atomic.AtomicBoolean(false)
    fun hasSavedKey(): Boolean = synchronized(storageLock) { !savedKeyRevoked.get() && credentialStore.contains("api-key") }
    @Synchronized fun begin(): Long {
        if (pendingForgets != 0) throw GoveeNameException(GoveeNameFailure.CLEANUP)
        return epoch.incrementAndGet()
    }
    @Synchronized fun cancel() { epoch.incrementAndGet() }
    fun import(key: String?, remember: Boolean, request: Long): Int {
        if (epoch.get() != request) throw GoveeNameException(GoveeNameFailure.CANCELLED)
        val selected = key ?: synchronized(storageLock) {
            if (savedKeyRevoked.get()) throw GoveeNameException(GoveeNameFailure.CLEANUP)
            credentialStore.getString("api-key", null)
        } ?: throw GoveeNameException(GoveeNameFailure.KEY)
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
                    if (persist) savedKeyRevoked.set(false)
                }
            } catch (e: Exception) {
                // A cancellation racing a commit restores the previous credential, not merely its absence.
                if (persist) {
                    val restore = credentialStore.edit()
                    if (previous == null) restore.remove("api-key") else restore.putString("api-key", previous)
                    val restored = try { restore.commit() } catch (_: Exception) { false }
                    if (!restored) {
                        savedKeyRevoked.set(true)
                        throw GoveeNameException(GoveeNameFailure.STORAGE).apply {
                            if (e is GoveeNameException) addSuppressed(GoveeNameException(e.reason))
                        }
                    }
                }
                throw e
            }
        }
        return result.size
    }
    /** Main-safe intent fence: prevent a newer import overtaking queued secret cleanup. */
    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    @Synchronized fun requestForgetAsync(): Deferred<Unit> {
        cancel(); names.value = emptyList(); savedKeyRevoked.set(true); pendingForgets++; cleanup.value = true
        return cleanupScope.async(start = CoroutineStart.ATOMIC) {
            try { synchronized(storageLock) {
                try {
                    if (!credentialStore.edit().remove("api-key").commit()) throw GoveeNameException(GoveeNameFailure.STORAGE)
                    savedKeyRevoked.set(false)
                } catch (_: Exception) { savedKeyRevoked.set(true); throw GoveeNameException(GoveeNameFailure.STORAGE) }
            } }
            finally { synchronized(this) { pendingForgets--; cleanup.value = pendingForgets != 0 } }
        }
    }
}
