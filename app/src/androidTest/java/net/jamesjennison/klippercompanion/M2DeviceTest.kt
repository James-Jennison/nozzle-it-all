package net.jamesjennison.klippercompanion

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import java.net.ServerSocket

class M2DeviceTest {
    @get:Rule val compose=createComposeRule()

    // WO-16: printingAllowsMacroPreparationButDisablesDispatch's "Run" button never appeared on
    // any fresh device (CI, emulator, AWS Device Farm) - only on the developer's own physical
    // Razr phones. Two real, independent bugs, not device-dependent flakiness:
    // (1) MainActivity.kt's Control tab only shows "Favorite macros" (with the "Run" button) for
    //     macros where macroOptions[name]?.favorite==true (an owner-requested change,
    //     2026-09-22) - macroOptions itself lives in SharedPreferences ("macro-options", keyed
    //     by a SHA-256 hash of the printer address), not in ScreenState, so this test could
    //     never set it up through its own setContent call. Only ever "passed" on the physical
    //     Razr phones from leftover state (a "TEST" macro favorited by hand during earlier
    //     manual testing) - confirmed via a real SharedPreferences read at test time. Fixed by
    //     seeding that exact preference entry before setContent, matching MainActivity.kt's own
    //     key derivation exactly, and clearing it afterward so this test doesn't leak state into
    //     any other test reusing the same fixture address.
    // (2) Even with (1) fixed, onNodeWithText("Run").performScrollTo() still failed - confirmed
    //     via a full semantics-tree dump that the Control tab's LazyColumn hadn't composed that
    //     far down yet (VerticalScrollAxisRange showed real unscrolled content;
    //     performScrollTo() needs the target node to already exist in the tree, which a
    //     virtualized LazyColumn item that's never been scrolled into view doesn't). Fixed by
    //     scrolling the container itself via performScrollToNode(hasText("Run")) - the same
    //     pattern this suite's own DashboardTestNavigation.kt already uses correctly elsewhere.
    private val macroFixtureAddress = "http://fixture.local/"
    private fun macroPrefsKey() = java.security.MessageDigest.getInstance("SHA-256")
        .digest(macroFixtureAddress.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun macroPrefs() = InstrumentationRegistry.getInstrumentation().targetContext
        .getSharedPreferences("macro-options", 0)

    @Before fun favoriteTheTestMacro() {
        macroPrefs().edit().putString(macroPrefsKey(), MacroTools.encode(mapOf("TEST" to MacroOptions(favorite = true)))).apply()
    }

    @After fun clearMacroFixtureState() {
        macroPrefs().edit().remove(macroPrefsKey()).apply()
    }
    @Test fun macroFormRejectsInjectionBeforePreparingCommand() {
        var command:PrinterCommand?=null
        compose.setContent {CompanionTheme {MacroForm("TEST",MacroOptions(parameters="TEMP=0,300,200"),{}, {command=it})}}
        compose.onNodeWithText("TEMP").performTextReplacement("200;G28")
        compose.onNodeWithText("Review command").performClick()
        compose.onNodeWithText("TEMP: enter a plain number.").assertIsDisplayed();assertNull(command)
        compose.onNodeWithText("TEMP").performTextReplacement("210")
        compose.onNodeWithText("Review command").performClick()
        assertEquals("TEST TEMP=210",command!!.arguments["script"])
    }
    // WO-16, continued: even with (1) and (2) above fixed, the final assertions
    // (onNodeWithText("Command: TEST").assertIsDisplayed(),
    // onNodeWithText("Confirm").assertIsNotEnabled()) were unreachable for a third, deeper
    // reason - confirmed via a real semantics-node-count dump after a real 3s wait:
    // MacroReviewPanel (opened by clicking "Review command") has no way to receive a fake
    // MacroReader through CompanionScreen, which hardcodes the real factory
    // (`state.moonrakerFor(a)`) with no override parameter - so its own live check() call
    // makes a genuine network request to the fake "http://fixture.local/" host and simply never
    // resolves in any automated environment (CI, emulator, Device Farm), the dump showed 0
    // nodes for "Command: TEST", "macro-notice", "macro-script", and "confirm-macro" alike,
    // meaning the panel was still stuck on "Checking the printer is idle…" indefinitely.
    // Separately, even a *successful* check would never produce a disabled "Confirm" button:
    // MacroTools.prepare() throws when printState isn't in allowedStates (which "printing"
    // isn't), so MacroReviewPanel's own catch block sets a notice message and never sets
    // `pending` at all - the confirm button doesn't render disabled, it doesn't render.
    // Fixed by testing MacroReviewPanel directly with an injected fake MacroReader (the same
    // pattern already used elsewhere in this suite for reader-dependent panels, e.g.
    // BedMeshPanelDeviceTest's fake MeshReader) instead of routing through CompanionScreen's
    // hardcoded live network dependency - this verifies the exact same real
    // MacroTools.prepare()/allowedStates logic, deterministically.
    @Test fun printingAllowsMacroPreparationButDisablesDispatch() {
        var sent=0
        compose.setContent {CompanionTheme {CompanionScreen(ScreenState(address="http://fixture.local/",connected=true,snapshot=PrinterSnapshot(true,"printing"),catalog=Catalog(emptyList(),listOf("TEST"),emptyList(),emptyList())),{},{},{},{_,_->sent++})}}
        compose.onNodeWithTag("nav-1").performClick()
        compose.onNodeWithTag("screen-list").performScrollToNode(hasText("Run"))
        compose.onNodeWithText("Run").performClick()
        compose.onNodeWithText("Review command").performClick()
        compose.onNodeWithText("Run TEST").assertIsDisplayed()
    }

    @Test fun macroReviewPanelBlocksDispatchWhilePrinting() {
        var sent=0
        val command = MacroTools.command("TEST", emptyList(), emptyMap())
        val reader = object : MacroReader { override fun macroStatus(name: String) = MacroStatus(true, "printing", true); override fun close() {} }
        compose.setContent { CompanionTheme { MacroReviewPanel(command, ScreenState(address="http://fixture.local/", connected=true), { _, _ -> sent++ }, {}) { reader } } }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("macro-notice").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("macro-notice").assertTextEquals("Macros require an idle, ready printer.")
        compose.onNodeWithTag("confirm-macro").assertDoesNotExist()
        assertEquals(0,sent)
    }
    @Test fun approvedRealPrinterFileDownloadsAndPreviewsWithoutMutation() {
        val args=androidx.test.platform.app.InstrumentationRegistry.getArguments()
        val endpoint=args.getString("printerUrl");val file=args.getString("previewFile")
        org.junit.Assume.assumeTrue("Explicit read-only file target required",!endpoint.isNullOrBlank()&&!file.isNullOrBlank())
        lateinit var workspace:FileWorkspace
        compose.setContent {val context=LocalContext.current;val scope=rememberCoroutineScope();workspace=remember{FileWorkspace(context,scope)};CompanionTheme {FileWorkspacePanel(workspace)}}
        try {
            compose.runOnIdle {workspace.download(endpoint!!,file!!)}
            compose.waitUntil(120000){!workspace.loading}
            assertNotNull(workspace.note,workspace.localFile)
            compose.onNodeWithText("Preview layers").performClick()
            compose.waitUntil(120000){!workspace.loading}
            assertNotNull(workspace.note,workspace.preview)
            assertTrue(workspace.preview!!.segments.isNotEmpty())
            compose.onNodeWithText("Extrusion layer",substring=true).assertIsDisplayed()
        }finally{compose.runOnIdle{workspace.close()}}
    }

    @Test fun contentDocumentImportIsLocalAndPreviewable() {
        lateinit var workspace:FileWorkspace
        compose.setContent {val context=LocalContext.current;val scope=rememberCoroutineScope();workspace=remember{FileWorkspace(context,scope)};CompanionTheme {FileWorkspacePanel(workspace)}}
        try {
            compose.runOnIdle {workspace.import(android.net.Uri.parse("content://${androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.packageName}.gcodefixture/sample"))}
            compose.waitUntil(20000){!workspace.loading}
            assertNotNull(workspace.note,workspace.localFile);assertEquals("fixture.gcode",workspace.name)
            compose.onNodeWithText("Preview layers").performClick();compose.waitUntil(10000){!workspace.loading}
            assertNotNull(workspace.note,workspace.preview)
            val destination=java.io.File(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"export-test-${java.util.UUID.randomUUID()}.gcode")
            try {
                destination.writeText("old data".repeat(100))
                val expected=workspace.localFile!!.readBytes()
                assertTrue(android.os.ParcelFileDescriptor.parseMode("wt") and android.os.ParcelFileDescriptor.MODE_TRUNCATE != 0)
                compose.runOnIdle {workspace.export(android.net.Uri.fromFile(destination))}
                compose.waitUntil(10000){!workspace.loading}
                assertEquals("Copy saved to the selected document.",workspace.note)
                assertArrayEquals(expected,destination.readBytes())
            }finally{destination.delete()}
            compose.runOnIdle {workspace.import(android.net.Uri.parse("content://${androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.packageName}.gcodefixture/wrong"))}
            compose.waitUntil(10000){!workspace.loading}
            assertEquals("Choose a .gcode or .gco file with a valid name.",workspace.note)
        }finally{compose.runOnIdle{workspace.close()}}
    }

    @Test fun downloadedFixtureRendersLayersAndReleasesWorkspaceFile() {
        val data="G21\nG90\nM83\nG0 X0 Y0 Z0.2\nG1 X20 E1\nG1 Y20 E1\nG1 X0 E1\nG1 Y0 E1\nG0 Z0.4\nG1 X10 E1\n".toByteArray()
        val server=ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"))
        val worker=Thread {runCatching {server.accept().use {socket->val reader=socket.getInputStream().bufferedReader();while(!reader.readLine().isNullOrEmpty()){};socket.getOutputStream().apply{write("HTTP/1.1 200 OK\r\nContent-Length: ${data.size}\r\n\r\n".toByteArray());write(data);flush()}}}}.apply{isDaemon=true;start()}
        lateinit var workspace:FileWorkspace
        try {
            compose.setContent {val context=LocalContext.current;val scope=rememberCoroutineScope();workspace=remember{FileWorkspace(context,scope)};CompanionTheme {FileWorkspacePanel(workspace)}}
            compose.runOnIdle {workspace.download("http://127.0.0.1:${server.localPort}/","fixture.gcode")}
            compose.waitUntil(10000){workspace.localFile!=null&&!workspace.loading}
            compose.onNodeWithText("Preview layers").performClick()
            compose.waitUntil(10000){workspace.preview!=null}
            compose.onNodeWithText("Extrusion layer 1 / 2",substring=true).assertIsDisplayed()
            val file=workspace.localFile!!
            compose.runOnIdle {
                val old=android.os.StrictMode.getThreadPolicy()
                try {android.os.StrictMode.setThreadPolicy(android.os.StrictMode.ThreadPolicy.Builder().detectDiskWrites().penaltyDeath().build());workspace.close()}
                finally{android.os.StrictMode.setThreadPolicy(old)}
            }
            compose.waitUntil(3000){!file.exists()}
        }finally{server.close();worker.join(1000)}
    }
}
