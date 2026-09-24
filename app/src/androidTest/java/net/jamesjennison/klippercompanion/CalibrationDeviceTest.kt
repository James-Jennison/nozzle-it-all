package net.jamesjennison.klippercompanion

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.jamesjennison.klippercompanion.project.AppDatabase
import net.jamesjennison.klippercompanion.project.ProjectViewModel
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.orcaslicer.engine.NativeEngine
import java.io.File

@RunWith(AndroidJUnit4::class)
class CalibrationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "test-calibration.db"
    private var openDb: AppDatabase? = null
    @After fun cleanup() = runBlocking {
        openDb?.close(); ctx.deleteDatabase(dbName)
        val vm = ProjectViewModel(ctx, AppDatabase.get(ctx).projectDao())
        AppDatabase.get(ctx).projectDao().observeProjects().first().filter { it.calibration != null }.forEach { if (vm.loadProject(it.id)) vm.deleteProject() }
    }

    private fun sliceSpec(spec: CalibrationSpec): String {
        val stl = File(ctx.cacheDir, "cal-${spec.kind.code}.stl"); MeshEdit.writeBinaryStl(Calibration.mesh(spec), stl)
        val out = File(ctx.cacheDir, "cal-${spec.kind.code}.gcode").also { it.delete() }
        val overrides = Calibration.overrides(spec)
        val pack = slicingProfilePack(SlicingPrinterModel.GENERIC_KLIPPER, null)!!
        NativeEngine.nativeResetCancel()
        NativeEngine.nativeSliceFile(stl.absolutePath, out.absolutePath, pack.materialize(ctx).toTypedArray(), overrides.keys.toTypedArray(), overrides.values.toTypedArray(), 0.0, 0.0, 0.0, 1.0)
        Calibration.applyToFile(out, spec)
        return out.readText()
    }

    @Test fun temperatureTowerSlicesWithTheRealEngineAndStepsTheTemperature() {
        val spec = CalibrationSpec(CalibrationKind.TEMPERATURE, 230, -5, 5)
        val gcode = sliceSpec(spec)
        val temps = gcode.lines().filter { it.startsWith("M104 S") && it.contains("calibration") }.map { it.substringAfter("S").substringBefore(" ").toInt() }
        assertEquals(listOf(225, 220, 215, 210), temps)
        val toolpath = File(ctx.cacheDir, "cal-temp.gcode").inputStream().buffered().use { GcodePreview.parse(it) }
        assertEquals(51f, toolpath.heights.max(), 0.5f)
        // each change lands at the top of the previous section (heights 11.x, 21.x, ...)
        val firstChange = gcode.lines().indexOfFirst { it.startsWith("M104 S225") }
        assertTrue(gcode.lines().take(firstChange).last { it.startsWith(";Z:") }.removePrefix(";Z:").toFloat() in 10.9f..11.5f)
    }

    @Test fun pressureAdvanceAndFlowModelsSliceAndTheTuningCommandsLand() {
        val pa = sliceSpec(CalibrationSpec(CalibrationKind.PRESSURE_ADVANCE))
        assertTrue(pa.contains("TUNING_TOWER COMMAND=SET_PRESSURE_ADVANCE")); assertTrue(pa.length > 5000)
        val flow = sliceSpec(CalibrationSpec(CalibrationKind.FLOW))
        assertFalse(flow.contains("TUNING_TOWER")); assertTrue(flow.length > 5000)
    }

    @Test fun calibrationProjectsPersistTheirSpecAndHoldTheGeneratedModel() = runBlocking<Unit> {
        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, dbName).build().also { openDb = it }
        val vm = ProjectViewModel(ctx, db.projectDao())
        val spec = CalibrationSpec(CalibrationKind.TEMPERATURE, 215, -5, 4)
        val project = vm.newCalibrationProject(spec)
        assertEquals(spec, CalibrationSpec.decode(vm.project.value!!.calibration))
        assertEquals(1, vm.objects.value.size)
        val g = MeshLoader.load(File(android.net.Uri.parse(vm.objects.value.single().sourceFileUri).path!!).absolutePath)
        assertEquals(1f + 4 * 10f, g.maxZ - g.minZ, 0.05f)
        val reopened = ProjectViewModel(ctx, db.projectDao()).also { assertTrue(it.loadProject(project.id)) }
        assertEquals(spec.encode(), reopened.project.value!!.calibration)
    }

    @Test fun theCalibrationDialogCreatesAProjectAndOpensItsEditor() {
        compose.onNodeWithTag("nav-2").performClick()
        compose.waitUntil(8000) { compose.onAllNodesWithTag("show-projects").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("show-projects").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("new-calibration").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("new-calibration").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("calibration-create").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("calibration-start").performTextClearance(); compose.onNodeWithTag("calibration-start").performTextInput("400")
        compose.onNodeWithTag("calibration-create").assertIsNotEnabled() // 400 °C is out of range
        compose.onNodeWithTag("calibration-start").performTextClearance(); compose.onNodeWithTag("calibration-start").performTextInput("220")
        compose.onNodeWithTag("calibration-create").assertIsEnabled().performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithTag("project-workspace").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("project-editor-title").assertTextContains("Temperature tower", substring = true)
    }
}
