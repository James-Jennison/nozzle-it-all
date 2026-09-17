package net.jamesjennison.klippercompanion

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BedMeshPanelDeviceTest {
    @get:Rule val compose = createComposeRule()
    @Test fun loadsAndDisplaysMeshHeatmap() {
        val reader = object : MeshReader {
            override fun meshStatus() = BedMeshStatus("default", listOf(10.0, 10.0), listOf(200.0, 200.0), listOf(listOf(0.01, -0.02), listOf(0.0, 0.03)))
            override fun close() {}
        }
        compose.setContent { CompanionTheme { BedMeshPanel("http://fixture.local/", true, {}, { reader }) } }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("mesh-canvas").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Profile: default").assertExists()
    }
    @Test fun missingMeshShowsExplicitEmptyState() {
        val reader = object : MeshReader {
            override fun meshStatus() = BedMeshStatus("", emptyList(), emptyList(), emptyList())
            override fun close() {}
        }
        compose.setContent { CompanionTheme { BedMeshPanel("http://fixture.local/", true, {}, { reader }) } }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("mesh-status").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("No active bed mesh reported by the printer.").assertExists()
        compose.onNodeWithTag("mesh-canvas").assertDoesNotExist()
    }
    @Test fun disconnectedShowsExplicitMessageWithoutQuerying() {
        var queried = false
        val reader = object : MeshReader {
            override fun meshStatus(): BedMeshStatus { queried = true; throw ApiFailure("Should not be called") }
            override fun close() {}
        }
        compose.setContent { CompanionTheme { BedMeshPanel("http://fixture.local/", false, {}, { reader }) } }
        compose.onNodeWithText("Disconnected. Connect to read the bed mesh.").assertExists()
        assertFalse(queried)
    }
}
