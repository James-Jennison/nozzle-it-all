package net.jamesjennison.klippercompanion
import org.junit.Test
import org.junit.Assert.*
class FileChangeSimulationTest {
 private val upload=FileChangeSimulation.Operation.UPLOAD
 @Test fun uploadRenameDeleteAndPayloadOwnership() {
  val s=FileChangeSimulation();val bytes="G90\n".toByteArray();val d=s.prepare(upload,"","new.gcode",bytes);bytes[0]=0;s.confirm(d.id)
  assertEquals("G90\n",s.contents("new.gcode")!!.decodeToString())
  val copy=s.contents("new.gcode")!!;copy[0]=0;assertEquals("G90\n",s.contents("new.gcode")!!.decodeToString())
  s.confirm(s.prepare(FileChangeSimulation.Operation.RENAME,"new.gcode","folder/new.gcode").id)
  assertNull(s.contents("new.gcode"));assertNotNull(s.contents("folder/new.gcode"))
  s.confirm(s.prepare(FileChangeSimulation.Operation.DELETE,"folder/new.gcode","").id);assertNull(s.contents("folder/new.gcode"));s.close()
 }
 @Test fun collisionAndInvalidPathsPreserveExistingBytes() {
  val s=FileChangeSimulation();val original=s.contents("example.gcode")
  for(name in listOf("example.gcode","../evil.gcode","/evil.gcode","a//b.gcode","a\\b.gcode","bad.txt","a\n.gcode"))assertThrows(IllegalArgumentException::class.java){s.prepare(upload,"",name,byteArrayOf(1))}
  assertArrayEquals(original,s.contents("example.gcode"));assertEquals(1,s.list().names.size)
 }
 @Test fun staleConfirmationCannotMutateAndCannotBeReplayed() {
  val s=FileChangeSimulation();val d=s.prepare(FileChangeSimulation.Operation.DELETE,"example.gcode","");s.simulateOtherClient()
  assertThrows(IllegalArgumentException::class.java){s.confirm(d.id)};assertNotNull(s.contents("example.gcode"))
  assertThrows(IllegalArgumentException::class.java){s.confirm(d.id)}
 }
 @Test fun cancelledAndSupersededDraftsCannotExecute() {
  val s=FileChangeSimulation();val d=s.prepare(upload,"","cancel.gcode",byteArrayOf(1));s.cancel()
  assertThrows(IllegalArgumentException::class.java){s.confirm(d.id)};assertNull(s.contents("cancel.gcode"))
  val old=s.prepare(upload,"","old.gcode",byteArrayOf(1));val next=s.prepare(upload,"","new.gcode",byteArrayOf(2))
  assertThrows(IllegalArgumentException::class.java){s.confirm(old.id)};s.confirm(next.id);assertNull(s.contents("old.gcode"))
 }
 @Test fun interruptionBeforeCommitNeverPublishesPartialFile() {
  val s=FileChangeSimulation();val before=s.list();val d=s.prepare(upload,"","partial.gcode",ByteArray(1024))
  assertThrows(IllegalStateException::class.java){s.confirm(d.id,FileChangeSimulation.Fault.INTERRUPTED)}
  assertEquals(before,s.list());assertNull(s.contents("partial.gcode"));assertThrows(IllegalArgumentException::class.java){s.confirm(d.id)}
 }
 @Test fun lostAcknowledgementCommitsOnceAndRequiresReadback() {
  val s=FileChangeSimulation();val d=s.prepare(upload,"","uncertain.gcode",byteArrayOf(7))
  assertThrows(IllegalStateException::class.java){s.confirm(d.id,FileChangeSimulation.Fault.LOST_ACK)}
  assertThrows(IllegalArgumentException::class.java){s.confirm(d.id)};assertArrayEquals(byteArrayOf(7),s.contents("uncertain.gcode"));assertEquals(1,s.list().revision)
 }
 @Test fun competingClientCannotExceedStorageQuota() {
  val s=FileChangeSimulation()
  for(i in 1..15)s.confirm(s.prepare(upload,"","$i.gcode",ByteArray(FileChangeSimulation.MAX_BYTES)).id)
  s.confirm(s.prepare(upload,"","last.gcode",ByteArray(FileChangeSimulation.MAX_BYTES-10)).id)
  val before=s.list();assertThrows(IllegalArgumentException::class.java){s.simulateOtherClient()};assertEquals(before,s.list())
  val small=FileChangeSimulation()
  for(i in 1..99)small.confirm(small.prepare(upload,"","$i.gcode",byteArrayOf(1)).id)
  assertThrows(IllegalArgumentException::class.java){small.simulateOtherClient()};assertEquals(100,small.list().names.size)
 }
 @Test fun boundsAndClosedSessionFailWithoutMutation() {
  val s=FileChangeSimulation()
  for(bytes in listOf(byteArrayOf(),ByteArray(FileChangeSimulation.MAX_BYTES+1)))assertThrows(IllegalArgumentException::class.java){s.prepare(upload,"","too-large.gcode",bytes)}
  for(i in 1..15)s.confirm(s.prepare(upload,"","$i.gcode",ByteArray(FileChangeSimulation.MAX_BYTES)).id)
  assertThrows(IllegalArgumentException::class.java){s.prepare(upload,"","16.gcode",ByteArray(FileChangeSimulation.MAX_BYTES))}
  s.close();assertThrows(IllegalStateException::class.java){s.list()}
 }
}
