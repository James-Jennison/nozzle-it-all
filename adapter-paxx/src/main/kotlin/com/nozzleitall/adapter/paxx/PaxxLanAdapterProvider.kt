package com.nozzleitall.adapter.paxx

import com.nozzleitall.printer.DeviceAdapterProvider

class PaxxLanAdapterProvider : DeviceAdapterProvider { override fun create() = PaxxLanAdapter() }
