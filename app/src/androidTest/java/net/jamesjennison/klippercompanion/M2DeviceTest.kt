package net.jamesjennison.klippercompanion

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import java.net.ServerSocket

class M2DeviceTest {
    @get:Rule val compose=createComposeRule()
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
    @Test fun printingAllowsMacroPreparationButDisablesDispatch() {
        var sent=0
        compose.setContent {CompanionTheme {CompanionScreen(ScreenState(address="http://fixture.local/",connected=true,snapshot=PrinterSnapshot(true,"printing"),catalog=Catalog(emptyList(),listOf("TEST"),emptyList(),emptyList())),{},{},{},{_,_->sent++})}}
        compose.onNodeWithTag("nav-1").performClick();compose.onNodeWithText("Run").performScrollTo().performClick()
        compose.onNodeWithText("Review command").performClick()
        compose.onNodeWithText("Command: TEST").assertIsDisplayed();compose.onNodeWithText("Confirm").assertIsNotEnabled();assertEquals(0,sent)
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
            compose.runOnIdle {workspace.import(android.net.Uri.parse("content://net.jamesjennison.klippercompanion.test.gcodefixture/sample"))}
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
            compose.runOnIdle {workspace.import(android.net.Uri.parse("content://net.jamesjennison.klippercompanion.test.gcodefixture/wrong"))}
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
