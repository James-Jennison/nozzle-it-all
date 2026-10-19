package com.nozzleitall.environment

/** Host-configurable bounded defaults approved for B-D; no hidden adaptive radio behavior. */
data class EnvironmentalLimits(val discoverySeconds: Int = 45, val queueCapacity: Int = 256,
    val pageSize: Int = 500, val retentionDays: Int = 30, val historyBytes: Long = 100L * 1024 * 1024) {
    init {
        require(discoverySeconds in 5..120 && queueCapacity in 16..1024 && pageSize in 1..500)
        require(retentionDays in 1..3650 && historyBytes in 1024 * 1024..100L * 1024 * 1024)
    }
}
