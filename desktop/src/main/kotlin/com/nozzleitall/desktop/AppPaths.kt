package com.nozzleitall.desktop

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * Where Nozzle It All keeps its own data. These locations belong to Nozzle only: nothing here is shared with, read
 * from, or written to OrcaSlicer's or Snapmaker Orca's folders (~/.config/OrcaSlicer, ~/.config/Snapmaker_Orca).
 *
 * Linux follows the XDG base-directory spec; Windows uses %APPDATA% / %LOCALAPPDATA%. NOZZLE_HOME overrides everything
 * (tests and portable installs).
 */
class AppPaths(val config: File, val data: File, val cache: File) {
    val printers get() = File(config, "printers.json")
    val secrets get() = File(config, "printer-secrets.json")
    val settings get() = File(config, "settings.json")
    val projects get() = File(data, "projects")
    val workspaceSessions get() = File(data, "workspace-sessions")
    val workspaceProfile get() = File(data, "advanced-workspace")
    val logs get() = File(cache, "logs")
    val slices get() = File(cache, "slices")

    fun ensure(): AppPaths {
        listOf(config, data, cache, projects, workspaceSessions, workspaceProfile, logs, slices).forEach { it.mkdirs() }
        runCatching { Files.setPosixFilePermissions(config.toPath(), PosixFilePermissions.fromString("rwx------")) }
        return this
    }

    companion object {
        const val APP_ID = "nozzle-it-all"
        /** Folders other slicers use. Nozzle never writes there; tests assert the resolved paths never overlap them. */
        val foreignFolderNames = listOf("OrcaSlicer", "Snapmaker_Orca", "Snapmaker Orca", "BambuStudio", "PrusaSlicer")

        fun resolve(env: Map<String, String> = System.getenv(), home: String = System.getProperty("user.home"),
                    os: String = System.getProperty("os.name")): AppPaths {
            env["NOZZLE_HOME"]?.takeIf { it.isNotBlank() }?.let { File(it) }?.let { return AppPaths(File(it, "config"), File(it, "data"), File(it, "cache")) }
            return if (os.startsWith("Windows", ignoreCase = true)) {
                val roaming = env["APPDATA"] ?: "$home\\AppData\\Roaming"
                val local = env["LOCALAPPDATA"] ?: "$home\\AppData\\Local"
                AppPaths(File(roaming, "Nozzle It All"), File(local, "Nozzle It All"), File(local, "Nozzle It All\\Cache"))
            } else {
                fun xdg(name: String, fallback: String) = env[name]?.takeIf { it.startsWith("/") } ?: "$home/$fallback"
                AppPaths(File(xdg("XDG_CONFIG_HOME", ".config"), APP_ID), File(xdg("XDG_DATA_HOME", ".local/share"), APP_ID), File(xdg("XDG_CACHE_HOME", ".cache"), APP_ID))
            }
        }
    }
}
