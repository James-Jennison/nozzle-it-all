package net.jamesjennison.klippercompanion

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ConfigSavePanelDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val original = "[printer]\nkinematics: corexy"
    private val autoSection = "#*# <-- SAVE_CONFIG -->\n#*# [bed_mesh default]"
    private fun readerFactory(autoSectionOverride: String = autoSection): (String) -> ConfigFileReader = { object : ConfigFileReader {
        override fun configFile() = ConfigFileContent("printer.cfg", original, autoSectionOverride)
        override fun close() {}
    } }
    private fun await() { compose.waitUntil(5000) { compose.onAllNodesWithTag("config-user-section").fetchSemanticsNodes().isNotEmpty() } }

    @Test fun editSectionIsHiddenWithoutAWriterFactory() {
        compose.setContent { CompanionTheme { ConfigFilePanel("http://fixture.local/", true, {}, readerFactory()) } }
        await()
        compose.onNodeWithTag("edit-config").assertDoesNotExist()
    }

    @Test fun backsUpBeforeWritingAndReportsTheBackupPath() {
        val backups = mutableListOf<String>()
        val writes = mutableListOf<Pair<String, String>>()
        val writerFactory: (String) -> ConfigWriter = { object : ConfigWriter {
            override fun backupConfig(filename: String): String { backups.add(filename); return "printer.cfg.bak-fixture" }
            override fun writeConfig(filename: String, content: String) { writes.add(filename to content) }
            override fun close() {}
        } }
        compose.setContent { CompanionTheme { ConfigFilePanel("http://fixture.local/", true, {}, readerFactory(), ready = true, printState = "standby", writerFactory = writerFactory) } }
        await()
        compose.onNodeWithTag("edit-config").performClick()
        compose.onNodeWithTag("config-editor").performTextReplacement("[printer]\nkinematics: corexy\nmax_velocity: 400")
        compose.onNodeWithTag("review-config-save").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("confirm-config-save").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, backups.size)
        compose.onNodeWithTag("confirm-config-save").performClick()
        compose.waitUntil(5000) { backups.isNotEmpty() }
        assertEquals(listOf("printer.cfg"), backups)
        assertEquals(1, writes.size); assertEquals("printer.cfg", writes.single().first)
        assertTrue(writes.single().second.contains("max_velocity: 400"))
        assertTrue(writes.single().second.contains(autoSection))
        compose.waitUntil(5000) { compose.onAllNodesWithText("Backed up the previous version to printer.cfg.bak-fixture", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun rejectsSavingTheSaveConfigMarkerIntoTheEditedSection() {
        compose.setContent { CompanionTheme { ConfigFilePanel("http://fixture.local/", true, {}, readerFactory(), ready = true, printState = "standby", writerFactory = { error("should not be called") }) } }
        await()
        compose.onNodeWithTag("edit-config").performClick()
        compose.onNodeWithTag("config-editor").performTextReplacement("#*# <---------------------- SAVE_CONFIG ---------------------->\n")
        compose.onNodeWithTag("review-config-save").performClick()
        compose.waitUntil(3000) { compose.onAllNodesWithTag("config-save-notice").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("confirm-config-save").assertDoesNotExist()
    }

    @Test fun restartRequiresASeparateConfirmationAndUsesHostRestart() {
        val sent = mutableListOf<PrinterCommand>()
        compose.setContent { CompanionTheme { ConfigFilePanel("http://fixture.local/", true, {}, readerFactory(), ready = true, printState = "standby", execute = { c, _ -> sent.add(c) }) } }
        await()
        compose.onNodeWithTag("arm-restart").performClick()
        assertEquals(0, sent.size)
        compose.onNodeWithTag("confirm-restart").performClick()
        assertEquals(1, sent.size)
        assertEquals("printer/restart", sent.single().path)
    }

    @Test fun printingBlocksEditingAndRestart() {
        val sent = mutableListOf<PrinterCommand>()
        compose.setContent { CompanionTheme { ConfigFilePanel("http://fixture.local/", true, {}, readerFactory(), ready = true, printState = "printing", writerFactory = { error("should not be called") }, execute = { c, _ -> sent.add(c) }) } }
        await()
        compose.onNodeWithTag("edit-config").assertIsNotEnabled()
        compose.onNodeWithTag("arm-restart").assertIsNotEnabled()
    }
}
