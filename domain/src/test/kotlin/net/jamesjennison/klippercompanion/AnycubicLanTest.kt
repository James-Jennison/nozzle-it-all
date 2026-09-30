package net.jamesjennison.klippercompanion

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

// Fixtures, and where each comes from (docs/upstream/PROVENANCE.md P-0036). None is a capture from a printer.
// - FROM THE PRIMARY'S TESTS (anycubic-orca-plugin 458eee7 tests/test_plugin.py): the /info body (lines 71-77), the handshake
//   master token, IV and credentials (lines 47-56; the ciphertext below was made from them with `openssl enc -aes-128-cbc`,
//   the same encryption the test performs, lines 59-65), the status reports (lines 250-266, 287-303), the ACE report
//   (lines 319-358), the PETG mapping case (lines 915-970) and the upload reply (lines 1066-1070).
// - FROM KOBRA-CONNECT'S DOC (kobra-connect 3edba24 docs/mqtt-commands.md): the info query (lines 54-63), the info report
//   (lines 68-97) and the pause / resume / stop / temperature messages (lines 188-293).
// - CONSTRUCTED from the references' code: the short-IV ciphertext (kobra_connect/handshake.py:53's NUL padding, made with
//   openssl), the `sign` expectation (md5 of md5, computed with md5sum per anycubic_lan.py:434-436), the pause-flag and
//   `box_info.slot_info` shapes (kobra_connect/moonraker_bridge/state.py:243-246; anycubic_lan.py:1198-1199).
class AnycubicLanTest {
    private val masterToken = "0123456789ABCDEFfedcba9876543210"
    private val infoBody = """{"code":200,"token":"$masterToken","modelId":"20025","ctrlInfoUrl":"http://10.30.14.52:18910/ctrl",
        "urls":{"fileUploadurl":"http://10.30.14.52:18910/gcode_upload"}}"""
    private val ctrlCipher = "RWu3jnViU/zFOyPAwWeg6/JI8IeDqG2E3pfLm4Fsd6N8NHibxMpxHEStQbN6xyeSDgcagjA7Dk2xjaIpl73KnE7tk5OTZ2+v0BP3CzjW5s2fcW4umwRArp+jS2ngMstBdCF1maEA+tx6a+fKqqEz70XR3s5+I6vb+cOaYyqcG+8="

    private val aceReport = JSONObject("""{"multi_color_box":[{"id":0,"loaded_slot":1,"temp":34,"humidity":20,"slots":[
        {"index":0,"type":"PLA Matte","color":[239,237,227],"status":5,"sku":"AHYGOW-101"},
        {"index":1,"type":"PETG","color":[207,79,128],"status":4,"sku":"AHPLMG-107"},
        {"index":2,"type":"PLA+","color":[117,120,123],"status":5,"sku":"AHPLPGY-108"},
        {"index":3,"type":"PLA","color":[72,74,73],"status":5,"sku":"AHYGBK-101"}]}]}""")

    // ---- gate ---------------------------------------------------------------------------------------------------------

    @Test fun startAndControlsAreGatedWithTheSharedWording() {
        assertFalse(AnycubicLan.START_VERIFIED)
        assertFalse(startVerifiedFor(PrinterKind.ANYCUBIC_LAN))
        val start = assertThrows(ApiFailure::class.java) { AnycubicLan.requireStartVerified("cube.gcode") }.message!!
        assertEquals("Uploaded cube.gcode to the printer but did not start it: starting a print on an Anycubic printer from Nozzle It All isn't verified on real hardware yet. Start it from the printer's screen.", start)
        assertTrue(AnycubicLan.startNotVerified("a.gcode", uploaded = false).startsWith("Did not start a.gcode: "))
        val control = assertThrows(ApiFailure::class.java) { AnycubicLan.requireControlVerified("pausing a print") }.message!!
        assertEquals("Nothing was sent: pausing a print on an Anycubic printer from Nozzle It All isn't verified on real hardware yet. Use the printer's screen.", control)
        AnycubicLan.requireStartVerified("x", verified = true); AnycubicLan.requireControlVerified("x", verified = true)
        val c = capabilitiesFor(PrinterKind.ANYCUBIC_LAN)
        assertEquals(PrinterTransport.ANYCUBIC_LAN, c.transport); assertFalse(c.supportsPauseResumeCancel); assertFalse(c.supportsJog)
        assertFalse(c.supportsKlipperExtras); assertFalse(c.verifiedOnRealHardware); assertTrue(c.acceptsOnDeviceSlicedGcode)
    }

    // ---- handshake ----------------------------------------------------------------------------------------------------

    @Test fun signIsMd5OfMd5OfTheTokenHalfTimestampAndNonce() {
        assertEquals("e43df9b5a46b755ea8f1b4dd08265544", AnycubicLan.md5Hex("0123456789ABCDEF"))
        assertEquals("b94ca3a38cd8db1df74c600a73e91bc1", AnycubicLan.sign(masterToken, 1733257941899L, "aB3dE9"))
        assertEquals(listOf("ts" to "1733257941899", "nonce" to "aB3dE9", "sign" to "b94ca3a38cd8db1df74c600a73e91bc1", "did" to "D1"),
            AnycubicLan.ctrlQuery(masterToken, 1733257941899L, "aB3dE9", "D1"))
    }

    @Test fun nonceAndDeviceIdHaveTheReferencesShapes() {
        val r = java.util.Random(7)
        repeat(20) { assertTrue(Regex("^[A-Za-z0-9]{6}$").matches(AnycubicLan.nonce(r))) }
        repeat(20) { assertTrue(Regex("^[A-Z0-9]{32}$").matches(AnycubicLan.newDeviceId(r))) }
    }

    @Test fun parsesInfoAndKeepsItsSecretsOutOfToString() {
        val info = AnycubicLan.parseInfo(infoBody)
        assertEquals(masterToken, info.token); assertEquals("20025", info.modelId); assertEquals("http://10.30.14.52:18910/ctrl", info.ctrlInfoUrl)
        assertEquals("http://10.30.14.52:18910/gcode_upload", info.fileUploadUrl)
        assertFalse(info.toString().contains(masterToken)); assertFalse(info.toString().contains("gcode_upload"))
        // AGENTS.md section 4.1 of the primary: the top-level form, with its ?s= token.
        val top = AnycubicLan.parseInfo("""{"token":"$masterToken","modelId":20024,"ctrlInfoUrl":"http://h/ctrl","fileUploadurl":"http://h:18910/gcode_upload?s=abc","cn":"DF09-EC93"}""")
        assertEquals("20024", top.modelId); assertEquals("http://h:18910/gcode_upload?s=abc", top.fileUploadUrl); assertEquals("DF09-EC93", top.serial)
    }

    @Test fun cloudModeAndOlderPrintersAreRefused() {
        assertEquals(AnycubicLan.CLOUD_MODE, assertThrows(ApiFailure::class.java) { AnycubicLan.parseInfo("""{"ctrlType":"cloud","token":"$masterToken","modelId":"20025","ctrlInfoUrl":"http://h/ctrl"}""") }.message)
        for (body in listOf("""{"modelId":"20025","ctrlInfoUrl":"http://h/ctrl"}""", """{"token":"$masterToken","ctrlInfoUrl":"http://h/ctrl"}""",
                """{"token":"$masterToken","modelId":"20025"}""", """{"token":"short","modelId":"1","ctrlInfoUrl":"http://h/ctrl"}""", "<html>"))
            assertEquals(body, AnycubicLan.NOT_LAN_HANDSHAKE, assertThrows(ApiFailure::class.java) { AnycubicLan.parseInfo(body) }.message)
    }

    @Test fun urlsThePrinterHandsOutMustBePlainHttpOnThePrinter() {
        assertEquals("http://10.30.14.52:18910/ctrl", AnycubicLan.printerUrl("http://10.30.14.52:18910/ctrl", "10.30.14.52"))
        assertNull(AnycubicLan.printerUrl("http://10.30.14.99:18910/ctrl", "10.30.14.52"))
        assertNull(AnycubicLan.printerUrl("https://10.30.14.52/ctrl", "10.30.14.52"))
        assertNull(AnycubicLan.printerUrl("file:///etc/passwd", "10.30.14.52"))
        assertNull(AnycubicLan.printerUrl("", "10.30.14.52"))
        assertEquals("http://[fe80::1]:18910/ctrl", AnycubicLan.printerUrl("http://[fe80::1]:18910/ctrl", "fe80::1"))
    }

    @Test fun decryptsTheCtrlReplyAsThePrimarysTestEncryptsIt() {
        val creds = AnycubicLan.parseCtrl("""{"code":200,"data":{"token":"1234567890123456","info":"$ctrlCipher"}}""", masterToken)
        assertEquals("test_user", creds.username); assertEquals("test_password", creds.password); assertEquals("dev12345678", creds.deviceId)
        assertEquals(9883, creds.brokerPort); assertEquals("", creds.deviceCert)
        assertFalse(creds.toString().contains("test_password")); assertFalse(creds.toString().contains("test_user"))
    }

    @Test fun aShortIvIsNulPaddedAsKobraConnectDoesAndTheBrokerPortAndClientCertAreRead() {
        val cipher = "oitqGaMfFV6OH9htu2YXP9M9ia3pg7yJFqcoN2ydnfXXRlvhdrnot4Bzs+Rmxs+f3lVkC8qcQge83vdoKWlrQ+yNuVkI5DRO9P7ATK5W37dcovJRKQ5DwyXt4HDSFqYCb/q9Jx5YmhbVD7V376rZ48S7WYFdXzvZNmdHkHFk39ZGa7P+BBsuUmUzZxF/uPv4"
        val creds = AnycubicLan.parseCtrl("""{"code":200,"data":{"token":"shortiv","info":"$cipher"}}""", masterToken)
        assertEquals("u2", creds.username); assertEquals("abcDEF123", creds.deviceId); assertEquals(9884, creds.brokerPort)
        assertEquals("CERT", creds.deviceCert); assertEquals("KEY", creds.deviceKey)
    }

    @Test fun aRefusedOrGarbledHandshakeIsAnError() {
        assertTrue(assertThrows(ApiFailure::class.java) { AnycubicLan.parseCtrl("""{"code":401,"message":"sign error"}""", masterToken) }.message!!.contains("sign error"))
        assertTrue(assertThrows(ApiFailure::class.java) { AnycubicLan.parseCtrl("""{"code":200,"data":{"token":"1234567890123456","info":"AAAA"}}""", masterToken) }.message!!.contains("decrypted"))
        // The right ciphertext under the wrong key (another printer's token) can't be read either.
        assertThrows(ApiFailure::class.java) { AnycubicLan.parseCtrl("""{"code":200,"data":{"token":"1234567890123456","info":"$ctrlCipher"}}""", "0123456789ABCDEF0000000000000000") }
    }

    // ---- MQTT ---------------------------------------------------------------------------------------------------------

    @Test fun topicsFollowTheReferences() {
        assertEquals("anycubic/anycubicCloud/v1/slicer/printer/20025/dev1/info", AnycubicLan.commandTopic("20025", "dev1", "info"))
        assertEquals("anycubic/anycubicCloud/v1/web/printer/20025/dev1/print", AnycubicLan.commandTopic("20025", "dev1", "print", web = true))
        assertEquals("anycubic/anycubicCloud/v1/printer/+/20025/dev1/#", AnycubicLan.reportFilter("20025", "dev1"))
        for (bad in listOf("a/b", "+", "#", "", "x y")) assertThrows(ApiFailure::class.java) { AnycubicLan.reportFilter("20025", bad) }
    }

    @Test fun queriesHaveTheDocumentedShape() {
        val q = AnycubicLan.infoQuery()
        assertEquals("anycubic/anycubicCloud/v1/slicer/printer/20024/d/info", q.topic("20024", "d"))
        val p = q.payload("747b3bf5-6c54-45a7-97bb-67507d78d160", 1733257941899L)
        assertEquals(setOf("type", "action", "timestamp", "msgid", "data"), p.keySet())
        assertEquals("info", p.getString("type")); assertEquals("query", p.getString("action")); assertEquals(1733257941899L, p.getLong("timestamp"))
        assertEquals("747b3bf5-6c54-45a7-97bb-67507d78d160", p.getString("msgid")); assertTrue(p.isNull("data"))
        assertTrue(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[0-9a-f]{4}-[0-9a-f]{12}$").matches(q.payload().getString("msgid")))
        val ace = AnycubicLan.aceQuery().payload()
        assertEquals("multiColorBox", ace.getString("type")); assertEquals("getInfo", ace.getString("action")); assertTrue(ace.isNull("data"))
        assertEquals("anycubic/anycubicCloud/v1/slicer/printer/20024/d/multiColorBox", AnycubicLan.aceQuery().topic("20024", "d"))
    }

    @Test fun reportTypeComesFromThePayloadOrTheTopic() {
        assertEquals("info", AnycubicLan.reportType("x", JSONObject("""{"type":"info","action":"report"}""")))
        assertEquals("multiColorBox", AnycubicLan.reportType("anycubic/anycubicCloud/v1/printer/public/20025/d/multiColorBox/report", JSONObject("{}")))
        assertNull(AnycubicLan.reportType("anycubic/anycubicCloud/v1/printer/public/20025/d/info", JSONObject("{}")))
    }

    // ---- status -------------------------------------------------------------------------------------------------------

    @Test fun kobraConnectsDocumentedInfoReportIsAnIdlePrinter() {
        val report = JSONObject("""{"type":"info","action":"report","timestamp":100107,"msgid":"747b3bf5","state":"done","code":200,"msg":"done",
            "data":{"printerName":"My Kobra 3","urls":{"fileUploadurl":"http://{ip}:18910/gcode_upload?s=...","rtspUrl":"http://{ip}:18088/flv"},
            "model":"Anycubic Kobra 3","ip":"{ip}","version":"2.3.5.3","state":"free",
            "temp":{"curr_hotbed_temp":23,"curr_nozzle_temp":28,"target_hotbed_temp":0,"target_nozzle_temp":0},
            "print_speed_mode":2,"fan_speed_pct":0,"aux_fan_speed_pct":0}}""")
        assertEquals("info", AnycubicLan.reportType("t", report))
        val s = AnycubicLan.snapshot(report.getJSONObject("data"))
        assertTrue(s.ready); assertEquals("standby", s.state); assertEquals(28.0, s.nozzle!!, 0.0); assertEquals(23.0, s.bed!!, 0.0)
        assertEquals(0.0, s.nozzleTarget!!, 0.0); assertEquals("", s.filename); assertNull(s.currentLayer)
    }

    @Test fun thePrimarysPrintingStatus() {
        val s = AnycubicLan.snapshot(JSONObject("""{"state":"busy","temp":{"curr_nozzle_temp":210,"target_nozzle_temp":210,"curr_hotbed_temp":60,"target_hotbed_temp":60},
            "last_project":{"state":"printing","progress":42,"remain_time":35,"curr_layer":63,"total_layers":150,"filename":"benchy.gcode"}}"""))
        assertEquals("printing", s.state); assertEquals(0.42f, s.progress, 0.0001f); assertEquals(63, s.currentLayer); assertEquals(150, s.totalLayers)
        assertEquals("benchy.gcode", s.filename); assertEquals(210.0, s.nozzleTarget!!, 0.0); assertEquals(60.0, s.bed!!, 0.0)
        assertFalse(AnycubicLan.isIdle(s.state))
    }

    @Test fun thePrimarysIdleStatusWithAStoppedLastProjectIsReady() {
        val s = AnycubicLan.snapshot(JSONObject("""{"state":"free","temp":{"curr_nozzle_temp":28,"target_nozzle_temp":0,"curr_hotbed_temp":25,"target_hotbed_temp":0},
            "last_project":{"state":"stoped","progress":100,"remain_time":0,"curr_layer":50,"total_layers":50,"filename":"model.gcode"}}"""))
        assertEquals("cancelled", s.state); assertEquals("standby", s.displayState); assertEquals("", s.activeFilename)
        assertTrue(AnycubicLan.isIdle(s.state))
    }

    @Test fun projectStatesAndThePauseFlag() {
        fun state(project: String, raw: String = "free") = AnycubicLan.stateName(JSONObject("""{"state":"$raw","project":$project}"""))
        assertEquals("paused", state("""{"state":"printing","pause":1}"""))
        assertEquals("paused", state("""{"state":"printing","pause":2}"""))
        assertEquals("printing", state("""{"state":"printing","pause":3}"""))
        assertEquals("cancelled", state("""{"state":"printing","pause":4}"""))
        assertEquals("paused", state("""{"state":"pause"}""")); assertEquals("paused", state("""{"state":"paused"}"""))
        assertEquals("complete", state("""{"state":"finish"}""")); assertEquals("complete", state("""{"state":"complete"}"""))
        assertEquals("error", state("""{"state":"error"}"""))
        assertEquals("printing", state("""{"state":"preparing"}""", raw = "busy"))
        assertEquals("standby", AnycubicLan.stateName(JSONObject("""{"state":"free"}""")))
        // project wins over last_project when both are sent.
        assertEquals("printing", AnycubicLan.stateName(JSONObject("""{"state":"free","project":{"state":"printing"},"last_project":{"state":"finish"}}""")))
        // print_time is in minutes.
        assertEquals(600.0, AnycubicLan.snapshot(JSONObject("""{"state":"busy","project":{"state":"printing","print_time":10}}""")).printDuration!!, 0.0)
    }

    // ---- ACE ----------------------------------------------------------------------------------------------------------

    @Test fun readsThePrimarysAceReport() {
        val slots = AnycubicLan.parseAce(aceReport)
        assertEquals(listOf(0, 1, 2, 3), slots.map { it.tool })
        assertTrue(slots.all { it.loaded })
        assertEquals(listOf("#EFEDE3", "#CF4F80", "#75787B", "#484A49"), slots.map { it.colorHex })
        assertEquals(listOf(false, true, false, false), slots.map { it.active })
        assertEquals("PLA Matte", slots[0].type); assertEquals("AHYGOW-101", slots[0].sku); assertEquals(listOf(239, 237, 227), slots[0].rgb)
        val shown = AnycubicLan.filamentSlots(slots)
        assertEquals(listOf("ACE 1 · slot 1", "ACE 1 · slot 2", "ACE 1 · slot 3", "ACE 1 · slot 4"), shown.map { it.name })
        assertEquals(listOf("PLA MATTE", "PETG", "PLA+", "PLA"), shown.map { it.material })
        assertTrue(shown[1].active)
    }

    @Test fun emptyAndMissingSlotsAreUnloadedAndTheAlternativeShapesAreRead() {
        val slots = AnycubicLan.parseAce(JSONObject("""{"multiColorBox":[{"loaded_slot":-1,"box_info":{"slot_info":[
            {"index":2,"type":"PETG","color":[1,2,3],"status":0},{"index":0,"type":"","color":[0,0,0],"status":1},{"index":3,"type":"TPU","status":1}]}}]}"""))
        assertEquals(listOf(0, 1, 2, 3), slots.map { it.tool })
        assertEquals(listOf(false, false, false, true), slots.map { it.loaded })
        assertNull(slots[2].type); assertNull(slots[2].colorHex)
        assertEquals("TPU", slots[3].type); assertNull("no colour reported", slots[3].colorHex)
        assertTrue(slots.none { it.active })
        val shown = AnycubicLan.filamentSlots(slots)
        assertNull(shown[0].material); assertEquals("TPU", shown[3].material)
        assertEquals(emptyList<AnycubicLan.AceSlot>(), AnycubicLan.parseAce(JSONObject("{}")))
        assertEquals(emptyList<AnycubicLan.AceSlot>(), AnycubicLan.parseAce(null))
        // A second box numbers its slots from tool 4.
        val two = AnycubicLan.parseAce(JSONObject("""{"multi_color_box":[{"slots":[]},{"loaded_slot":0,"slots":[{"index":0,"type":"PLA","color":[9,9,9],"status":5}]}]}"""))
        assertEquals(8, two.size); assertEquals(4, two.single { it.loaded }.tool); assertEquals("ACE 2 · slot 1", two.single { it.loaded }.label)
    }

    @Test fun baseTypesFollowThePrimarysCatalog() {
        assertEquals("PLA", AnycubicLan.baseType("PLA Matte")); assertEquals("PLA", AnycubicLan.baseType("PLA+"))
        assertEquals("PETG", AnycubicLan.baseType("PETG-CF")); assertEquals("PETG", AnycubicLan.baseType("Rapid PETG"))
        assertEquals("TPU", AnycubicLan.baseType("tpu 95a")); assertEquals("PLA", AnycubicLan.baseType("")); assertEquals("PLA", AnycubicLan.baseType(null))
        assertEquals("PLA", AnycubicLan.baseType("Wood"))
    }

    // ---- print start (gated) ------------------------------------------------------------------------------------------

    @Test fun mappingResolvesThePrimarysPetgSlot() {
        val slots = AnycubicLan.parseAce(JSONObject("""{"multi_color_box":[{"slots":[
            {"index":0,"type":"PLA Matte","sku":"AHYGOW-101","status":5,"color":[239,237,227]},
            {"index":1,"type":"PETG","sku":"","status":5,"color":[244,0,49]},
            {"index":2,"type":"PLA+","sku":"AHPLPGY-108","status":5,"color":[117,120,123]},
            {"index":3,"type":"PLA Matte","sku":"AHYGBK-101","status":5,"color":[72,74,73]}]}]}"""))
        val m = AnycubicLan.boxMapping(listOf(1), slots)
        assertEquals(listOf(AnycubicLan.BoxMapping(0, 1, listOf(244, 0, 49), "PETG")), m)
        val j = m.single().toJson()
        assertEquals("[244,0,49,255]", j.getJSONArray("paint_color").toString()); assertEquals("[244,0,49]", j.getJSONArray("ams_color").toString())
        // The file's declared type wins over the slot's; "GENERIC" doesn't.
        assertEquals("PLA", AnycubicLan.boxMapping(listOf(1), slots, listOf(SlicedFileFilaments.Filament(0, "PLA Silk", null))).single().materialType)
        assertEquals("PETG", AnycubicLan.boxMapping(listOf(1), slots, listOf(SlicedFileFilaments.Filament(0, "GENERIC", null))).single().materialType)
        // Two file tools, one unmapped.
        assertEquals(listOf(0 to 3, 2 to 0), AnycubicLan.boxMapping(listOf(3, -1, 0), slots).map { it.paintIndex to it.amsIndex })
        // Single colour: slot 1 of the first ACE, as the primary's default.
        assertEquals(listOf(AnycubicLan.BoxMapping(0, 0, listOf(239, 237, 227), "PLA")), AnycubicLan.boxMapping(emptyList(), slots))
        // No ACE: no mapping.
        assertEquals(emptyList<AnycubicLan.BoxMapping>(), AnycubicLan.boxMapping(emptyList(), emptyList()))
    }

    @Test fun badMappingsAreRefusedBeforeAnythingIsBuilt() {
        val slots = AnycubicLan.parseAce(JSONObject("""{"multi_color_box":[{"slots":[{"index":0,"status":0},{"index":1,"type":"PLA","status":5,"color":[1,1,1]}]},
            {"slots":[{"index":0,"type":"PLA","status":5,"color":[2,2,2]}]}]}"""))
        assertTrue(assertThrows(IllegalArgumentException::class.java) { AnycubicLan.boxMapping(listOf(9), slots) }.message!!.contains("doesn't report"))
        assertTrue(assertThrows(IllegalArgumentException::class.java) { AnycubicLan.boxMapping(listOf(2), slots) }.message!!.contains("nothing loaded"))
        assertTrue(assertThrows(IllegalArgumentException::class.java) { AnycubicLan.boxMapping(listOf(4), slots) }.message!!.contains("first ACE"))
        assertTrue(assertThrows(IllegalArgumentException::class.java) { AnycubicLan.boxMapping(emptyList(), slots) }.message!!.contains("nothing loaded"))
    }

    @Test fun startMessageIsThePrimarysFullForm() {
        val cmd = AnycubicLan.startPrint("benchy.gcode", 188000L, listOf(AnycubicLan.BoxMapping(0, 1, listOf(244, 0, 49), "PETG")), flowCalibration = true)
        assertEquals("anycubic/anycubicCloud/v1/slicer/printer/20025/d/print", cmd.topic("20025", "d"))
        val p = cmd.payload("m", 1L)
        assertEquals("print", p.getString("type")); assertEquals("start", p.getString("action"))
        val d = p.getJSONObject("data")
        assertEquals("-1", d.getString("taskid")); assertEquals("benchy.gcode", d.getString("filename")); assertEquals(1, d.getInt("filetype"))
        assertEquals(1, d.getInt("project_type")); assertEquals(188000L, d.getLong("filesize")); assertTrue(d.isNull("filepath"))
        assertEquals("934ad649cb2e71f39df0fc44a687c8bf", d.getString("md5")) // md5("benchy.gcode"), as anycubic_lan.py:841
        assertEquals("https://anycubic.com/store/aaa.gcode", d.getString("url"))
        val ams = d.getJSONObject("ams_settings")
        assertTrue(ams.getBoolean("use_ams"))
        val entry = ams.getJSONArray("ams_box_mapping").getJSONObject(0)
        assertEquals(0, entry.getInt("paint_index")); assertEquals(1, entry.getInt("ams_index")); assertEquals("PETG", entry.getString("material_type"))
        val task = d.getJSONObject("task_settings")
        assertEquals(1, task.getInt("auto_leveling")); assertEquals(0, task.getInt("vibration_compensation")); assertEquals(1, task.getInt("flow_calibration"))
        assertEquals(0, task.getInt("dry_mode")); assertEquals(0, task.getJSONObject("timelapse").getInt("status"))
        assertEquals(0, task.getJSONObject("drying_settings").getInt("target_temp")); assertEquals(0, task.getJSONArray("model_objects_skip_parts").length())
        // No ACE: use_ams false and no mapping.
        val plain = AnycubicLan.startPrint("a.gcode", 1L, emptyList()).payload().getJSONObject("data").getJSONObject("ams_settings")
        assertFalse(plain.getBoolean("use_ams")); assertEquals(0, plain.getJSONArray("ams_box_mapping").length())
    }

    // ---- controls (gated) ---------------------------------------------------------------------------------------------

    @Test fun controlsHaveTheReferencesShapes() {
        for ((cmd, action) in listOf(AnycubicLan.pause() to "pause", AnycubicLan.resume() to "resume", AnycubicLan.stop() to "stop")) {
            assertEquals("anycubic/anycubicCloud/v1/slicer/printer/m/d/print", cmd.topic("m", "d"))
            val p = cmd.payload(); assertEquals(action, p.getString("action")); assertEquals("""{"taskid":"-1"}""", p.getJSONObject("data").toString())
        }
        val temp = AnycubicLan.setTemperatures(210, null)
        assertEquals("anycubic/anycubicCloud/v1/web/printer/m/d/print", temp.topic("m", "d"))
        assertEquals("update", temp.action)
        assertEquals(210, temp.payload().getJSONObject("data").getJSONObject("settings").getInt("target_nozzle_temp"))
        assertFalse(temp.payload().getJSONObject("data").getJSONObject("settings").has("target_hotbed_temp"))
        assertEquals(60, AnycubicLan.setTemperatures(null, 60).payload().getJSONObject("data").getJSONObject("settings").getInt("target_hotbed_temp"))
        assertThrows(IllegalArgumentException::class.java) { AnycubicLan.setTemperatures(null, null) }
        assertEquals("""{"axis":4,"move_type":2,"distance":0}""".let { JSONObject(it).toMap() }, (AnycubicLan.homeAll().data as JSONObject).toMap())
        val feed = (AnycubicLan.feedFilament(2, 1).data as JSONObject).getJSONArray("multi_color_box").getJSONObject(0)
        assertEquals(0, feed.getInt("id")); assertEquals(2, feed.getJSONObject("feed_status").getInt("slot_index")); assertEquals(1, feed.getJSONObject("feed_status").getInt("type"))
        val dry = (AnycubicLan.setDrying(true).data as JSONObject).getJSONArray("multi_color_box").getJSONObject(0).getJSONObject("drying_status")
        assertEquals(1, dry.getInt("status")); assertEquals(55, dry.getInt("target_temp")); assertEquals(240, dry.getInt("duration")); assertEquals(240, dry.getInt("remain_time"))
        assertEquals(0, (AnycubicLan.setDrying(false).data as JSONObject).getJSONArray("multi_color_box").getJSONObject(0).getJSONObject("drying_status").getInt("target_temp"))
    }

    // ---- upload -------------------------------------------------------------------------------------------------------

    @Test fun uploadGoesToThePrintersOwnUploadUrlWithThePrimarysHeaders() {
        val info = AnycubicLan.parseInfo("""{"token":"$masterToken","modelId":"20025","ctrlInfoUrl":"http://10.30.14.52:18910/ctrl","fileUploadurl":"http://10.30.14.52:18910/gcode_upload?s=tok"}""")
        assertEquals("http://10.30.14.52:18910/gcode_upload?s=tok", AnycubicLan.uploadUrl(info, "10.30.14.52", 18910))
        assertNull("another host's upload URL isn't used", AnycubicLan.uploadUrl(info, "10.30.14.53", 18910))
        val noUrl = AnycubicLan.parseInfo("""{"token":"$masterToken","modelId":"20025","ctrlInfoUrl":"http://h/ctrl"}""")
        assertEquals("http://10.30.14.52:18910/gcode_upload", AnycubicLan.uploadUrl(noUrl, "10.30.14.52", 18910))
        val headers = AnycubicLan.uploadHeaders("DID32", 42L).toMap()
        assertEquals("AnycubicSlicerNext/1.3.7.3", headers["User-Agent"]); assertEquals("DID32", headers["X-BBL-Device-ID"]); assertEquals("42", headers["X-File-Length"])
        assertEquals(9, headers.size)
        assertEquals("uploaded_test.gcode", AnycubicLan.parseUploadReply("""{"code":200,"message":"success","data":{"gcode":"uploaded_test.gcode"}}""", "t.gcode"))
        assertTrue(assertThrows(ApiFailure::class.java) { AnycubicLan.parseUploadReply("""{"code":500,"message":"disk full"}""", "t.gcode") }.message!!.contains("disk full"))
        assertEquals("my_cube__2_.gcode", AnycubicLan.safeFileName("/x/my cube (2).gcode"))
    }
}
