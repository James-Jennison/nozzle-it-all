package com.nozzleitall.adapter.prusa

import com.nozzleitall.printer.DeviceAdapterProvider

class PrusaLinkAdapterProvider : DeviceAdapterProvider { override fun create() = PrusaLinkAdapter() }
