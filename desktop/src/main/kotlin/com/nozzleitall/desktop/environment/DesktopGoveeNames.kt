package com.nozzleitall.desktop.environment

import com.nozzleitall.environment.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*

/** Persist names, not keys. No startup IO, requests or equipment control. */
class DesktopGoveeNames(private val cache: GoveeNameCache? = null,
    private val fetch: (String, () -> Boolean) -> List<GoveeDeviceName> = { key, current -> GoveeNameApi().fetch(key, current) }) {
    private val epoch = AtomicLong()
    private val names = MutableStateFlow<List<GoveeDeviceName>>(emptyList())
    val devices = names.asStateFlow()
    private val storageLock = Any()
    private var loaded = false
    @Volatile private var loadFailed = false
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Lock-free snapshot for radio processing; never opens disk or network. */
    fun currentDevices(): List<GoveeDeviceName> {
        if (loadFailed) throw GoveeNameException(GoveeNameFailure.DIRECTORY)
        return names.value
    }
    private var pendingForgets = 0
    fun cachedDevices(): List<GoveeDeviceName> = synchronized(storageLock) {
        if (loadFailed) throw GoveeNameException(GoveeNameFailure.DIRECTORY)
        if (!loaded) {
            val request = epoch.get()
            val restored = try { cache?.read().orEmpty() } catch (e: GoveeNameException) { loaded = true; loadFailed = true; throw e }
            synchronized(this) { if (epoch.get() == request && pendingForgets == 0) { names.value = restored; loaded = true } }
        }
        names.value
    }
    @Synchronized fun begin(): Long {
        if (pendingForgets != 0) throw GoveeNameException(GoveeNameFailure.CLEANUP)
        return epoch.incrementAndGet()
    }
    @Synchronized fun cancel() { epoch.incrementAndGet() }
    @Synchronized private fun reserveForget() { cancel(); names.value = emptyList(); pendingForgets++ }
    fun forget() { reserveForget(); finishForget() }
    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    fun requestForgetAsync(): Deferred<Unit> {
        reserveForget()
        return cleanupScope.async(start = CoroutineStart.ATOMIC) { finishForget() }
    }
    private fun finishForget() {
        try { synchronized(storageLock) {
            loaded = true; loadFailed = true // Failed removal cannot silently resurrect the disk directory.
            cache?.write(emptyList()); loadFailed = false
        } }
        finally { synchronized(this) { pendingForgets-- } }
    }
    fun import(key: String, request: Long): Int {
        if (epoch.get() != request) throw GoveeNameException(GoveeNameFailure.CANCELLED)
        val result = fetch(key) { epoch.get() == request }
        synchronized(storageLock) {
            if (epoch.get() != request) throw GoveeNameException(GoveeNameFailure.CANCELLED)
            fun publish(move: () -> Unit) = synchronized(this) {
                if (epoch.get() != request || pendingForgets != 0) throw GoveeNameException(GoveeNameFailure.CANCELLED)
                move(); names.value = result; loaded = true; loadFailed = false
            }
            if (cache == null) publish {} else cache.write(result, ::publish)
        }
        return result.size
    }
}
