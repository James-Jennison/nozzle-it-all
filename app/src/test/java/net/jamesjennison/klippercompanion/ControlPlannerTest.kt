package net.jamesjennison.klippercompanion
import org.junit.Test
import org.junit.Assert.*
class ControlPlannerTest {
 private val axes=setOf("x","y","z")
 private val ready=ControlState(true,true,"standby",10000,7,mapOf("extruder" to 260.0,"heater_bed" to 110.0),true,true,axes,axes.associateWith{125.0},axes.associateWith{0.0},axes.associateWith{250.0},"cartesian",true,"extruder")
 private fun plan(a:ControlAction,v:String,s:ControlState=ready,now:Long=10000,g:Int=7)=ControlPlanner.preview(a,v,s,now,g)
 private fun blocked(a:ControlAction,v:String,s:ControlState=ready,now:Long=10000,g:Int=7) {assertThrows(IllegalArgumentException::class.java){plan(a,v,s,now,g)}}
 @Test fun allActionsRejectPrintingPausedOfflineStaleAndWrongConnection() {
  for(a in ControlAction.entries) {
   for(s in listOf(ready.copy(printState="printing"),ready.copy(printState="paused"),ready.copy(printState="unknown"),ready.copy(printState="error"),ready.copy(connected=false),ready.copy(ready=false),ready.copy(observedAt=0),ready.copy(observedAt=Long.MIN_VALUE))) blocked(a,a.initial,s)
   blocked(a,a.initial,g=8);blocked(a,a.initial,now=9999)
  }
 }
 @Test fun strictNumbersRejectInjectionAndNonfiniteInputs() {
  for(v in listOf("200\nG28","NaN","Infinity","1e2"," 200","200;M112","","1".repeat(25))) blocked(ControlAction.NOZZLE,v)
 }
 @Test fun temperaturesRequireKnownLimitsAndRejectOverflow() {
  assertEquals("SET_HEATER_TEMPERATURE HEATER=extruder TARGET=200",plan(ControlAction.NOZZLE,"200.0").script)
  assertEquals("SET_HEATER_TEMPERATURE HEATER=heater_bed TARGET=0",plan(ControlAction.BED,"0").script)
  blocked(ControlAction.NOZZLE,"261");blocked(ControlAction.BED,"111");blocked(ControlAction.BED,"-1")
  blocked(ControlAction.NOZZLE,"200",ready.copy(heaterLimits=emptyMap()))
  blocked(ControlAction.NOZZLE,"200",ready.copy(heaterLimits=mapOf("extruder" to Double.NaN)))
 }
 @Test fun fanAndOverridesAreBoundedAndCapabilityChecked() {
  assertEquals("M106 S127.5",plan(ControlAction.FAN,"50").script)
  assertEquals("M220 S100",plan(ControlAction.SPEED,"100").script)
  assertEquals("M221 S100",plan(ControlAction.FLOW,"100").script)
  blocked(ControlAction.FAN,"101");blocked(ControlAction.SPEED,"201");blocked(ControlAction.FLOW,"49")
  blocked(ControlAction.FAN,"10",ready.copy(fan=false));blocked(ControlAction.FLOW,"100",ready.copy(overrides=false))
 }
 @Test fun motionChecksHomingPositionBoundsAndKinematics() {
  val script=plan(ControlAction.X,"-10").script
  assertTrue(script.contains("G91\nG1 X-10 F1200"));assertTrue(script.endsWith("RESTORE_GCODE_STATE NAME=COMPANION_PREVIEW"));assertFalse(script.contains("MOVE=1"))
  blocked(ControlAction.X,"1",ready.copy(homed=setOf("x")))
  blocked(ControlAction.X,"1",ready.copy(kinematics="delta"))
  blocked(ControlAction.X,"1",ready.copy(positions=mapOf("x" to 250.0)))
  blocked(ControlAction.X,"-1",ready.copy(positions=mapOf("x" to 0.0)))
  blocked(ControlAction.X,"1",ready.copy(positions=emptyMap()))
  blocked(ControlAction.X,"1",ready.copy(maximum=mapOf("x" to Double.NaN)))
  blocked(ControlAction.Z,"11");blocked(ControlAction.Z,"0")
  assertTrue(plan(ControlAction.Z,"1").script.contains("F300"))
 }
 @Test fun exactDecimalBoundsMatchEmittedValues() {
  assertEquals("M106 S127.5000000000000000000255",plan(ControlAction.FAN,"50.00000000000000000001").script)
  blocked(ControlAction.X,"10.00000000000000000001")
  blocked(ControlAction.NOZZLE,"260.000000000000000001")
  blocked(ControlAction.BED,"110.000000000000000001")
  blocked(ControlAction.SPEED,"200.000000000000000001")
  blocked(ControlAction.FLOW,"150.000000000000000001")
  blocked(ControlAction.FAN,"100.000000000000000001")
  blocked(ControlAction.EXTRUDE,"5.00000000000000000001")
  blocked(ControlAction.X,"1.00000000000000000001",ready.copy(positions=mapOf("x" to 249.0)))
  assertTrue(plan(ControlAction.X,"10.00000000000000000000").script.contains("G1 X10 F1200"))
 }
 @Test fun scopedMotionRequiresOverrideCapability() {
  for(a in listOf(ControlAction.X,ControlAction.Y,ControlAction.Z,ControlAction.EXTRUDE)) blocked(a,"1",ready.copy(overrides=false))
 }
 @Test fun coldExtrusionAndRetractionBothBlockedAndModesAreRestored() {
  for(v in listOf("5","-5")) blocked(ControlAction.EXTRUDE,v,ready.copy(canExtrude=false))
  blocked(ControlAction.EXTRUDE,"5",ready.copy(extruder="extruder1"));blocked(ControlAction.EXTRUDE,"6")
  assertEquals("SAVE_GCODE_STATE NAME=COMPANION_PREVIEW\nM220 S100\nM221 S100\nM83\nG1 E-5 F120\nRESTORE_GCODE_STATE NAME=COMPANION_PREVIEW",plan(ControlAction.EXTRUDE,"-5").script)
  assertFalse(PrinterCommand::class.java.isAssignableFrom(ControlPreview::class.java))
 }
}
