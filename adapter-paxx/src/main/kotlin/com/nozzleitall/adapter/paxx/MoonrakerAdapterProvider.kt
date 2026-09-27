package com.nozzleitall.adapter.paxx

import com.nozzleitall.printer.DeviceAdapterProvider

class MoonrakerAdapterProvider : DeviceAdapterProvider { override fun create() = MoonrakerAdapter() }
