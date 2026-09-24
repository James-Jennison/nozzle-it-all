package net.jamesjennison.klippercompanion

fun ScreenState.capabilitiesFor(address: String): PrinterCapabilities = capabilitiesFor(kindFor(address))
