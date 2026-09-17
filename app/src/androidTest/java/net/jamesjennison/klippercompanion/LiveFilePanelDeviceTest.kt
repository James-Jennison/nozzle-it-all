package net.jamesjennison.klippercompanion

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class LiveFilePanelDeviceTest {
    @get:Rule val compose=createComposeRule()
    private val ready=ScreenState(address="http://fixture.local/",connected=true,snapshot=PrinterSnapshot(true,"standby"))
    private var confirmations=0
    private val backend=object:LiveFileBackend {
        override fun recoveryNotice()=""
        override fun prepare(operation:LiveFileChanges.Operation,source:String,requested:String,upload:File?)=LiveFileChanges.Draft("fixture",operation,source,"renamed-00000000-0000-4000-8000-000000000001.gcode",16,"fixture")
        override fun confirm(id:String):String {confirmations++;return "Verified rename: renamed-00000000-0000-4000-8000-000000000001.gcode"}
        override fun cancel(){}
        override fun close(){}
    }
    private fun mount(state:State<ScreenState> = mutableStateOf(ready)) {
        compose.setContent{val context=LocalContext.current;val scope=rememberCoroutineScope();val workspace=remember{FileWorkspace(context,scope)}
            CompanionTheme{LiveFilePanel(state.value,workspace,{}, {},{_,_->backend})}}
    }
    private fun review(){
        compose.onNodeWithText("Rename").performScrollTo().performClick()
        compose.onNodeWithTag("live-file-source").performScrollTo().performTextReplacement("source.gcode")
        compose.onNodeWithTag("live-file-name").performScrollTo().performTextReplacement("renamed.gcode")
        compose.onNodeWithTag("live-file-review").performScrollTo().performClick()
        compose.waitUntil(5000){compose.onAllNodesWithTag("live-file-draft").fetchSemanticsNodes().isNotEmpty()}
    }
    @Test fun destinationShownBeforeSingleConfirmation(){mount();review();assertEquals(0,confirmations)
        compose.onNodeWithTag("live-file-draft").performScrollTo().assertTextContains("renamed-00000000-0000-4000-8000-000000000001.gcode",substring=true)
        compose.onNodeWithTag("live-file-confirm").performScrollTo().performClick()
        compose.waitUntil(5000){confirmations==1};compose.onNodeWithTag("live-file-confirm").assertDoesNotExist()
    }
    @Test fun editsInvalidateReview(){mount();review();compose.onNodeWithTag("live-file-name").performScrollTo().performTextReplacement("another.gcode")
        compose.onNodeWithTag("live-file-confirm").assertDoesNotExist();assertEquals(0,confirmations)
    }
    @Test fun disconnectInvalidatesReview(){val state=mutableStateOf(ready);mount(state);review();compose.runOnIdle{state.value=ready.copy(connected=false)}
        compose.onNodeWithTag("live-file-confirm").assertDoesNotExist();compose.onNodeWithTag("live-file-review").performScrollTo().assertIsNotEnabled();assertEquals(0,confirmations)
    }
    @Test fun deletionNeedsAdditionalConsentAndEditsInvalidateIt(){mount()
        compose.onNodeWithText("Delete").performScrollTo().performClick()
        compose.onNodeWithTag("live-file-name").assertDoesNotExist()
        compose.onNodeWithTag("live-file-source").performScrollTo().performTextReplacement("source.gcode")
        compose.onNodeWithTag("live-file-review").performScrollTo().performClick()
        compose.waitUntil(5000){compose.onAllNodesWithTag("live-file-draft").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("live-file-confirm").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("live-file-delete-consent").performScrollTo().performClick()
        compose.onNodeWithTag("live-file-confirm").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("live-file-source").performScrollTo().performTextReplacement("other.gcode")
        compose.onNodeWithTag("live-file-confirm").assertDoesNotExist();assertEquals(0,confirmations)
    }
    @Test fun explicitDeletionConfirmationIsSingleUse(){mount()
        compose.onNodeWithText("Delete").performScrollTo().performClick()
        compose.onNodeWithTag("live-file-source").performScrollTo().performTextReplacement("source.gcode")
        compose.onNodeWithTag("live-file-review").performScrollTo().performClick()
        compose.waitUntil(5000){compose.onAllNodesWithTag("live-file-draft").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("live-file-delete-consent").performScrollTo().performClick()
        compose.onNodeWithTag("live-file-confirm").performScrollTo().performClick()
        compose.waitUntil(5000){confirmations==1};compose.onNodeWithTag("live-file-confirm").assertDoesNotExist()
    }

}
