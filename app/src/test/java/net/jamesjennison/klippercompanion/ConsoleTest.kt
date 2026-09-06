package net.jamesjennison.klippercompanion
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import org.json.JSONArray
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
class ConsoleTest {
 private fun row(message:String="ok",type:String="response",time:Double=1.0)=JSONObject().put("time",time).put("type",type).put("message",message)
 private fun batch(vararg rows:JSONObject)=JSONObject().put("gcode_store",JSONArray(rows.toList()))
 @Test fun boundedParsingPreservesRepeatedMessagesAndFlagsTruncation() {
  val p=ConsoleLog.parse(JSONObject().put("gcode_store",JSONArray((0..250).map{row("x".repeat(3000))})))
  assertEquals(200,p.entries.size);assertTrue(p.truncated);assertTrue(p.entries.all{it.message.length==2048})
  assertEquals(2,ConsoleLog.parse(batch(row(),row())).entries.size)
 }
 @Test fun rejectsMalformedSchemaAndUnknownTimeOrType() {
  for(r in listOf(JSONObject(),batch(row(type="unknown")),batch(row(time=-1.0)),batch(row(time=253402300800.0)),batch(JSONObject().put("time",1).put("type","response")))) {
   assertThrows(Exception::class.java){ConsoleLog.parse(r)}
  }
  assertTrue(ConsoleLog.parse(batch()).entries.isEmpty())
 }
 @Test fun filtersAreCaseInsensitiveAndCommandsAreNotErrors() {
  val p=ConsoleLog.parse(batch(row("!! heater fault"),row("Error reading sensor"),row("error_macro", "command"),row("ok")))
  assertEquals(2,ConsoleLog.filter(p.entries,"",true).size)
  assertEquals("!! heater fault",ConsoleLog.filter(p.entries,"HEATER",true).single().message)
  assertEquals("ok",ConsoleLog.filter(p.entries,"",false).first().message)
  assertTrue(ConsoleLog.filter(p.entries,"missing",false).isEmpty())
 }
 @Test fun copyIsBoundedAndControlCharactersAreRemoved() {
  assertEquals("a\nb",ConsoleLog.parse(batch(row("a\u0000\n\u001bb"))).entries.single().message)
  assertEquals(65536,ConsoleLog.copy(List(200){ConsoleEntry(1.0,"response","x".repeat(2048))}).length)
 }
 @Test fun endpointIsBoundedReadOnlyAndSupportsBasePath() {
  MockWebServer().use {s->s.start();Moonraker(s.url("/proxy/").toString()).use {api->
   s.enqueue(MockResponse().setBody(JSONObject().put("result",batch(row())).toString()))
   assertEquals("ok",api.console().entries.single().message)
   val r=s.takeRequest();assertEquals("GET",r.method);assertEquals("/proxy/server/gcode_store?count=200",r.path);assertEquals(0L,r.bodySize)
   s.enqueue(MockResponse().setResponseCode(302).addHeader("Location","/elsewhere"));assertThrows(ApiFailure::class.java){api.console()};assertEquals(2,s.requestCount)
   s.enqueue(MockResponse().setBody("x".repeat(2000001)));assertThrows(ApiFailure::class.java){api.console()}
  }}
 }
}
