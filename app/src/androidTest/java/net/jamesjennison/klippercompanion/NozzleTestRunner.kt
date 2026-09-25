package net.jamesjennison.klippercompanion

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/** Marks the first-run welcome as already seen, so every UI test that launches MainActivity reaches the real screens. */
class NozzleTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle?) {
        OnboardingPrefs.markDone(targetContext)
        super.onCreate(arguments)
    }
}
