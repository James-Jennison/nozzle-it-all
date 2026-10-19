package com.nozzleitall.desktop.environment

import com.nozzleitall.environment.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/** Session-only directory. No credential persistence, startup requests or equipment control. */
class DesktopGoveeNames(private val fetch: (String, () -> Boolean) -> List<GoveeDeviceName> = { key, current -> GoveeNameApi().fetch(key, current) }) {
    private val epoch = AtomicLong()
    private val names = MutableStateFlow<List<GoveeDeviceName>>(emptyList())
    val devices = names.asStateFlow()
    @Synchronized fun begin(): Long = epoch.incrementAndGet()
    @Synchronized fun cancel() { epoch.incrementAndGet() }
    @Synchronized fun forget() { cancel(); names.value = emptyList() }
    fun import(key: String, request: Long): Int {
        if (epoch.get() != request) throw GoveeNameException(GoveeNameFailure.CANCELLED)
        val result = fetch(key) { epoch.get() == request }
        synchronized(this) {
            if (epoch.get() != request) throw GoveeNameException(GoveeNameFailure.CANCELLED)
            names.value = result
        }
        return result.size
    }
}
