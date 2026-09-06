package net.jamesjennison.klippercompanion
import org.junit.Test
import org.junit.Assert.*
import java.io.ByteArrayInputStream
import java.io.InterruptedIOException
class P09Test {
 private fun parse(s:String)=GcodePreview.parse(ByteArrayInputStream(s.toByteArray()))
 @Test fun travelIsSeparateAndUsesPrecedingLayer() {
  val p=parse("G90\nM83\nG0 X0 Y0 Z0.2\nG0 X2\nG1 X10 E1\nG0 X12 Z0.4\nG1 X20 E1\nG1 Y5 E-1\n")
  assertEquals(2,p.segments.size);assertEquals(3,p.travels.size)
  assertEquals(listOf(0,0,1),p.travels.map{it.layer});assertEquals(12f,p.travels[1].x2)
 }
 @Test fun travelHonorsRelativeModesAndCoordinateReset() {
  val p=parse("G90\nM83\nG0 X0 Y0\nG1 X10 E1\nG92 X0\nG91\nG0 X2 Y3\nG1 X1 E1\n")
  assertEquals(10f,p.travels.single().x1);assertEquals(12f,p.travels.single().x2)
  assertEquals(13f,p.segments.last().x2)
 }
 @Test fun longFilesBoundBothKindsOfPathsAndDiscloseSampling() {
  val p=parse(buildString {append("G90\nM83\nG0 X0 Y0\n");repeat(130000){append("G1 X${it%2} E1\nG0 Y${it%2}\n")}})
  assertTrue(p.sampled);assertTrue(p.segments.size<120000);assertTrue(p.travels.size<60000)
  assertTrue(previewDrawList(p.segments,0).size<=5000)
 }
 @Test fun cancellationIsCheckedWithinReadBuffer() {
  var checks=0
  assertThrows(InterruptedIOException::class.java){GcodePreview.parse(ByteArrayInputStream("G0 X0 Y0\n".repeat(200).toByteArray())){++checks>20}}
  assertEquals(21,checks)
 }
 @Test fun omittedTravelArcsAreDisclosedAndNotStraightChords() {
  val p=parse("G90\nM83\nG0 X10 Y0\nG3 X0 Y10 I-10 J0\nG1 X1 E1\n")
  assertTrue(p.ignoredMotion);assertTrue(p.travels.isEmpty());assertEquals(1,p.segments.size)
 }
 @Test fun unsupportedTravelArcsUpdateEndpointsWithoutGeometryValidation() {
  for(prefix in listOf("G90\nM83\nG0 X10 Y0\nG3 X0 Y10 R10\n", "G28\nM83\nG2 X0 Y0 R5\n", "G90\nM83\nG0 X0 Y0\nG2 X5 Y6\n")) {
   val p=parse(prefix+"G1 X1 E1\n")
   assertTrue(p.ignoredMotion);assertTrue(p.travels.isEmpty());assertEquals(1,p.segments.size)
  }
  assertThrows(IllegalArgumentException::class.java){parse("G90\nM83\nG0 X10 Y0\nG3 X0 Y10 R10 E1\n")}
 }
}
