package com.nozzleitall.adapter.bambu

import com.nozzleitall.printer.DeviceAdapterProvider

class BambuLanAdapterProvider : DeviceAdapterProvider { override fun create() = BambuLanAdapter() }
