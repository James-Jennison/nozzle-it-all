package net.jamesjennison.klippercompanion
import org.junit.Test
import org.junit.Assert.*
class DashboardTest {
 @Test fun damagedPreferencesRecoverKnownCompleteLayout() {
  assertEquals(DashboardOptions(),DashboardOptions.decode("garbage"))
  val recovered=DashboardOptions.decode("""{"mode":"oops","accent":"bad","order":["Print","Print","Unknown"],"hidden":["Camera","Unknown"]}""")
  assertEquals(listOf("Print","Camera","Temperatures","Quick tools"),recovered.order)
  assertEquals(setOf("Camera"),recovered.hidden);assertEquals("Dark",recovered.mode)
 }
 @Test fun savedLayoutRoundTripsAndBoundsHold() {
  val saved=DashboardOptions("System","Blue",hidden=setOf("Camera")).move("Print",-1)
  assertEquals(saved,DashboardOptions.decode(saved.encode()))
  assertEquals(saved,saved.move("Print",-1));assertEquals(saved,saved.move("Missing",1))
 }
}
