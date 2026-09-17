package net.jamesjennison.klippercompanion

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import org.json.JSONObject

/** Explicit opt-in only. Creates comments-only disposable files; never starts a print. */
class LiveFileHardwareTest {
    @get:Rule val compose=createComposeRule()
    @Test fun approvedU1UploadRenameAndDeleteThroughActualPanel() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("approved_live_files")=="true")
        val address="http://192.168.1.110/"
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val proof=File(context.filesDir,"live-file-hardware-proof.json")
        val report=JSONObject().put("status","STARTED");proof.writeText(report.toString())
        val payload="; Klipper Companion disposable file acceptance\n; No printer commands\n"
        val server=ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));server.soTimeout=15000
        val worker=Thread{
            server.use{it.accept().use{socket->
                val reader=socket.getInputStream().bufferedReader();while(!reader.readLine().isNullOrEmpty()){}
                socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: ${payload.toByteArray().size}\r\nConnection: close\r\n\r\n"+payload).toByteArray())
            }}
        };worker.start()
        lateinit var workspace:FileWorkspace
        compose.setContent{
            val scope=rememberCoroutineScope();workspace=remember{FileWorkspace(context,scope)}
            CompanionTheme{LiveFilePanel(ScreenState(address=address,connected=true,snapshot=PrinterSnapshot(true,"standby")),workspace,{}, {})}
        }
        try {
            compose.runOnIdle{workspace.download("http://127.0.0.1:${server.localPort}/","fixture.gcode")}
            compose.waitUntil(15000){workspace.localFile!=null&&!workspace.loading}
            worker.join(2000)
            compose.onNodeWithTag("live-file-name").performScrollTo().performTextReplacement("codex-acceptance-upload.gcode")
            fun reviewAndConfirm(deleteSource:String?=null):String {
                compose.onNodeWithTag("live-file-review").performScrollTo().performClick()
                compose.waitUntil(30000){compose.onAllNodesWithTag("live-file-draft").fetchSemanticsNodes().isNotEmpty()}
                val node=compose.onNodeWithTag("live-file-draft").performScrollTo().fetchSemanticsNode()
                val text=node.config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString("\n"){it.text}
                val destination=deleteSource?:text.substringAfter("To: ").substringBefore('\n')
                assertTrue(destination.startsWith("codex-acceptance-"))
                report.put("pending_destination",destination);proof.writeText(report.toString())
                if(deleteSource!=null)compose.onNodeWithTag("live-file-delete-consent").performScrollTo().performClick()
                compose.onNodeWithTag("live-file-confirm").performScrollTo().performClick()
                compose.waitUntil(45000){compose.onAllNodes(hasTestTag("live-file-notice") and (hasText("Verified ",substring=true) or hasText("Outcome requires inspection",substring=true))).fetchSemanticsNodes().isNotEmpty()}
                val outcome=compose.onNodeWithTag("live-file-notice").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString("\n"){it.text}
                report.put("outcome",outcome);proof.writeText(report.toString());assertTrue(outcome,outcome.startsWith("Verified "))
                return destination
            }
            val uploaded=reviewAndConfirm();report.put("uploaded",uploaded);proof.writeText(report.toString())
            compose.onNodeWithText("Rename").performScrollTo().performClick()
            compose.onNodeWithTag("live-file-source").performScrollTo().performTextReplacement(uploaded)
            compose.onNodeWithTag("live-file-name").performScrollTo().performTextReplacement("codex-acceptance-renamed.gcode")
            val renamed=reviewAndConfirm();report.put("renamed",renamed);proof.writeText(report.toString())
            compose.onNodeWithText("Delete").performScrollTo().performClick()
            compose.onNodeWithTag("live-file-source").performScrollTo().performTextReplacement(renamed)
            val deleted=reviewAndConfirm(renamed);report.put("deleted",deleted).put("status","PASS");proof.writeText(report.toString())
        }finally{server.close();compose.runOnIdle{workspace.close()}}
    }
}
