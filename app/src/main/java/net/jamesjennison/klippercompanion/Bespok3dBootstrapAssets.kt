package net.jamesjennison.klippercompanion

import android.content.Context

// Phase 9S: reading the bundled packages out of the APK's assets is the only Android-specific step.
fun Bespok3dBootstrapPackages.load(context: Context): Bespok3dBootstrapSet = context.assets.open(ASSET_PATH).use { load(it) }
