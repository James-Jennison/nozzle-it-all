package net.jamesjennison.klippercompanion

/**
 * What the app must tell users to satisfy the AGPL-3.0 (this app, and the nozzle-engine slicer it embeds): the licence,
 * and where to get the complete corresponding source. Kept as plain data so a unit test can pin it to the repo's real
 * engine pin and LICENSE.
 */
object OpenSourceNotice {
    const val APP_LICENSE = "GNU Affero General Public License v3.0 or later"
    const val APP_SOURCE_URL = "https://github.com/James-Jennison/nozzle-it-all"
    const val APP_LICENSE_URL = "$APP_SOURCE_URL/blob/main/LICENSE"
    const val NOTICES_URL = "$APP_SOURCE_URL/blob/main/THIRD_PARTY_NOTICES.md"
    const val ENGINE_NAME = "nozzle-engine"
    const val ENGINE_SOURCE_URL = "https://github.com/James-Jennison/nozzle-engine"
    /** Must equal `base.commit` in engine/fork/ENGINE_PIN.json (a unit test enforces it). */
    const val ENGINE_COMMIT = "dc86dbf00d1d3239bf0a937cf0eefdb4459054b1"
    const val ENGINE_PIN_URL = "$APP_SOURCE_URL/blob/main/engine/fork/ENGINE_PIN.json"
    const val ENGINE_COMMIT_URL = "$ENGINE_SOURCE_URL/tree/$ENGINE_COMMIT"
    /** Every change made to the slicer since OrcaSlicer, one commit each. */
    const val ENGINE_HISTORY_URL = "$ENGINE_SOURCE_URL/commits/$ENGINE_COMMIT"

    /** The links shown in the About dialog: (label, url). */
    val links: List<Pair<String, String>> = listOf(
        "This app's source code" to APP_SOURCE_URL,
        "License text (AGPL-3.0)" to APP_LICENSE_URL,
        "Third-party notices" to NOTICES_URL,
        "Slicing engine source at the exact version used" to ENGINE_COMMIT_URL,
        "Slicing engine change history" to ENGINE_HISTORY_URL,
    )

    val statement: String =
        "Nozzle It All is free software under the $APP_LICENSE, and comes with no warranty. You may get, study, change and share " +
        "its complete source code, including the on-device slicer, at the addresses below. On-device slicing is $ENGINE_NAME " +
        "(also AGPL-3.0), commit ${ENGINE_COMMIT.take(12)}: OrcaSlicer's engine by way of Snapmaker Orca, with our changes to run " +
        "it headless on Android, in the browser and on the desktop."
}
