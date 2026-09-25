package net.jamesjennison.klippercompanion

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class OpenSourceNoticeTest {
    private fun repoFile(path: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) { File(dir, path).takeIf { it.exists() }?.let { return it }; dir = dir.parentFile }
        error("$path not found above ${File("").absoluteFile}")
    }

    @Test fun engineCommitMatchesThePinnedEngine() {
        val pin = repoFile("engine/ENGINE_PIN.json").readText()
        assertTrue("shown commit must be the pinned one", pin.contains("\"commit\": \"${OpenSourceNotice.ENGINE_COMMIT}\""))
    }

    @Test fun theLinkedPatchAndNoticesExistInTheRepo() {
        assertTrue(repoFile("engine/android-headless-engine.patch").isFile)
        assertTrue(repoFile("THIRD_PARTY_NOTICES.md").isFile)
        assertTrue(repoFile("LICENSE").readText().contains("GNU AFFERO GENERAL PUBLIC LICENSE"))
    }

    @Test fun everyLinkIsHttpsAndNamesTheRightHost() {
        assertEquals(5, OpenSourceNotice.links.size)
        OpenSourceNotice.links.forEach { (label, url) -> assertTrue(label, label.isNotBlank()); assertTrue(url, url.startsWith("https://github.com/")) }
        assertTrue(OpenSourceNotice.ENGINE_COMMIT_URL.endsWith(OpenSourceNotice.ENGINE_COMMIT))
        assertTrue(OpenSourceNotice.statement.contains(OpenSourceNotice.ENGINE_COMMIT.take(12)))
    }
}
