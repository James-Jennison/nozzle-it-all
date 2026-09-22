package net.jamesjennison.klippercompanion

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

// WO-13: AddPrinterWizard's happy path against the real Snapmaker U1 (192.168.1.110) used
// throughout tonight's device verification - proves the wizard's own live connectivity test
// step actually works and gates Finish for real, not just that its UI renders.
class AddPrinterWizardDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun addingTheRealU1CompletesEveryStepAndCommitsTheProfile() {
        var committed: PrinterProfile? = null
        var opened: String? = null
        compose.setContent {
            CompanionTheme {
                AddPrinterWizard(
                    existingAddresses = emptyList(),
                    addProfile = { p -> committed = p; null },
                    openPrinter = { opened = it },
                    close = {},
                )
            }
        }
        compose.onNodeWithTag("wizard-address").performTextInput("http://192.168.1.110/")
        compose.onNodeWithTag("wizard-next-1").performClick()
        // Step 2: slicing profile - leave "None" selected, proceed.
        compose.onNodeWithTag("wizard-next-2").performClick()
        // No Centauri Carbon selected, so the firmware step is skipped and this lands directly
        // on the connectivity test, which runs automatically against the real U1. Wait for the
        // real async result (Finish only becomes enabled on an actual pass), not just for the
        // step's UI to mount.
        compose.waitUntil(timeoutMillis = 15_000) {
            try { compose.onNodeWithTag("wizard-finish").assertIsEnabled(); true } catch (e: AssertionError) { false }
        }
        compose.onNodeWithTag("wizard-finish").performClick()
        assertNotNull("expected addProfile to be called with the committed profile", committed)
        assertEquals("http://192.168.1.110/", committed?.address)
        assertEquals("http://192.168.1.110/", opened)
    }
}
