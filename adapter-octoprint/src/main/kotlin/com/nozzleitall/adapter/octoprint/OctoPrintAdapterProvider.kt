package com.nozzleitall.adapter.octoprint

import com.nozzleitall.printer.DeviceAdapterProvider

class OctoPrintAdapterProvider : DeviceAdapterProvider { override fun create() = OctoPrintAdapter() }
