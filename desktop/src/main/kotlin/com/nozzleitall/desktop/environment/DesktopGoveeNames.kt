package com.nozzleitall.desktop.environment

import com.nozzleitall.environment.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/** Persist names, not keys. No startup IO, requests or equipment control. */
class DesktopGoveeNames(private val cache: GoveeNameCache? = null,
    private val fetch: (String, () -> Boolean) -> List<GoveeDeviceName> = { key, current -> GoveeNameApi().fetch(key, current) }) {
    private val epoch = AtomicLong()
    private val names = MutableStateFlow<List<GoveeDeviceName>>(emptyList())
    val devices = names.asStateFlow()
    private val storageLock = Any()
    private var loaded = false
    private var pendingForgets = 0
    fun cachedDevices(): List<GoveeDeviceName> = synchronized(storageLock) {
        if (!loaded) {
            val request = epoch.get()
            val restored = cache?.read().orEmpty()
            synchronized(this) { if (epoch.get() == request) { names.value = restored; loaded = true } }
        }
        names.value
    }
    @Synchronized fun begin(): Long {
        if (pendingForgets != 0) throw GoveeNameException(GoveeNameFailure.CLEANUP)
        return epoch.incrementAndGet()
    }
    @Synchronized fun cancel() { epoch.incrementAndGet() }
    fun forget() {
        synchronized(this) { cancel(); names.value = emptyList(); pendingForgets++ }
        try { synchronized(storageLock) { cache?.write(emptyList()); loaded = true } }
        finally { synchronized(this) { pendingForgets-- } }
    }
    fun import(key: String, request: Long): Int {
        if (epoch.get() != request) throw GoveeNameException(GoveeNameFailure.CANCELLED)
        val result = fetch(key) { epoch.get() == request }
        synchronized(storageLock) {
            if (epoch.get() != request) throw GoveeNameException(GoveeNameFailure.CANCELLED)
            fun publish(move: () -> Unit) = synchronized(this) {
                if (epoch.get() != request) throw GoveeNameException(GoveeNameFailure.CANCELLED)
                move(); names.value = result; loaded = true
            }
            if (cache == null) publish {} else cache.write(result, ::publish)
        }
        return result.size
    }
}
