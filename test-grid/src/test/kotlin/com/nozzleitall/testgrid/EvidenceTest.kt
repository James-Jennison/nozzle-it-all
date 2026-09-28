package com.nozzleitall.testgrid

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EvidenceTest {
    private fun run(preset: SimulatedPrinter.Preset = SimulatedPrinter.Preset.PAXX_U1, start: Long = 1_790_000_000_000L, faults: MutableSet<String> = mutableSetOf(),
                    suite: String = "paxx-u1"): Pair<Support.Started, EvidenceBundle> {
        val clock = Support.Clock(start)
        val st = Support.start(suite, SimulatedPrinter(preset, clock::now, faults), clock)
        Support.drive(st.session)
        return st to Support.build(st)
    }

    private fun text(b: EvidenceBundle) = b.files.filterKeys { EvidenceBundle.isText(it) }.values.joinToString("\n") { String(it, Charsets.UTF_8) }

    @Test fun bundleHoldsTheRequiredFieldsAndVerifies() {
        val (_, b) = run()
        val ev = b.evidence
        assertEquals("nozzle.evidence", ev.getString("format"))
        assertEquals(JSONArray("[1,0]").toString(), ev.getJSONArray("version").toString())
        listOf("run", "producer", "engine", "suite", "target", "inputs", "tests", "grades", "requirements", "interventions", "redaction").forEach { assertTrue(it, ev.has(it)) }
        assertEquals("0.1.0", ev.getJSONObject("producer").getString("version"))
        assertEquals("0123456789abcdef", ev.getJSONObject("producer").getString("sourceRevision"))
        assertEquals("dc86dbf00d1d3239bf0a937cf0eefdb4459054b1", ev.getJSONObject("engine").getString("commit"))
        val target = ev.getJSONObject("target")
        assertEquals("simulated", target.getString("kind"))
        assertEquals("paxx-extended", target.getJSONObject("firmware").getString("family"))
        assertEquals(64, target.getJSONObject("profile").getString("sha256").length)
        assertTrue(target.getJSONArray("capabilities").toList().contains("upload_job"))
        val input = ev.getJSONArray("inputs").getJSONObject(0)
        assertEquals(64, input.getJSONObject("gcode").getString("sha256").length)
        assertEquals(64, input.getJSONArray("modelParts").getJSONObject(0).getString("sha256").length)
        // Required photos are included, with metadata removed.
        val photo = b.files.keys.first { it.startsWith("attachments/print-single/first-layer") }
        val png = b.files.getValue(photo)
        assertFalse(String(png, Charsets.ISO_8859_1).contains("tEXt"))
        assertFalse(String(png, Charsets.ISO_8859_1).contains("GPS"))
        assertEquals("unsigned", b.integrity.getJSONObject("signing").getString("status"))
        assertTrue(BundleReader.read(b.zip()) is BundleReader.Result.Valid)
    }

    @Test fun simulatedEvidenceNeverGradesHardware() {
        val (_, b) = run()
        val grades = b.evidence.getJSONObject("grades").getJSONObject("single_material")
        Category.entries.forEach { assertEquals(it.id, "UNVERIFIED", grades.getJSONObject(it.id).getString("result")) }
        assertEquals("PASS", grades.getJSONObject("slicing").getString("recordedResult"))
    }

    @Test fun contentDigestIgnoresTimestampsAndIdsButBundleDigestDoesNot() {
        val (_, a) = run(start = 1_790_000_000_000L)
        val (_, b) = run(start = 1_800_000_000_000L)
        assertEquals(a.contentDigest, b.contentDigest)
        assertNotEquals(a.bundleDigest, b.bundleDigest)
        // Same bundle, same bytes.
        assertArrayEquals(a.zip(), a.zip())
        assertArrayEquals(a.zip(), EvidenceBundle(a.files).zip())
    }

    @Test fun differentOutcomesChangeTheContentDigest() {
        val (_, a) = run()
        val (_, b) = run(faults = mutableSetOf("guard_disabled"))
        assertNotEquals(a.contentDigest, b.contentDigest)
    }

    @Test fun noPrivateValueSurvivesIntoTheBundle() {
        // The lost reply puts the printer's address and API key into an error message and the log.
        val (st, b) = run(faults = mutableSetOf("lost_ack:home", "lost_ack:upload"))
        val all = text(b)
        st.target.localSecrets().forEach { assertFalse("leaked $it", all.contains(it, ignoreCase = true)) }
        listOf("192.168.50.23", "workshop-printer", "sim-4f9c2e71d8a3b6f05e1a", "/webcam", "X-Api-Key: sim", "Bearer sim", "/home/", "/tmp/").forEach { assertFalse("leaked $it", all.contains(it)) }
        assertTrue(all.contains("[private]") || all.contains("[redacted]"))
        assertTrue(b.evidence.getJSONObject("redaction").getJSONObject("counts").length() > 0)
        // Digests are not mistaken for secrets.
        assertTrue(all.contains(b.evidence.getJSONObject("suite").getString("digest")))
    }

    @Test fun representativeSecretsAndEndpointsAreRemoved() {
        val r = Redactor(listOf("Workshop U1", "hunter2-access"))
        val input = """
            Connecting to http://192.168.1.113:7125/printer/info for Workshop U1
            camera: http://192.168.1.113/webcam/webrtc and rtsp://10.0.0.5:554/stream1
            X-Api-Key: 0123456789abcdef0123456789abcdef
            Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJqaiJ9.c2lnbmF0dXJlLWhlcmU
            Cookie: session=abc123; theme=dark
            {"password": "hunter2", "access_code": "12345678", "serial": "01P00A123456789"}
            api_key=sk-live-51Habc token=ghp_abcdefghijklmnop
            printer at workshop-u1.local and fd7a:115c:a1e0::1234 and fe80::1ff:fe23:4567:890a
            mac 3c:22:fb:12:34:56, email jj@example.com
            log file /home/jjennison/Nozzle/run.log, /Users/jj/Desktop/x.gcode, C:\Users\James\x, /storage/emulated/0/Download/a.gcode, /data/user/0/com.nozzleitall.app/files/x
            -----BEGIN OPENSSH PRIVATE KEY-----
            b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQ
            -----END OPENSSH PRIVATE KEY-----
            access code hunter2-access
            public docs https://docs.opencentauri.cc/klipper-conversion?utm=1
            firmware 1.6.0.267_20260815150420, COSMOS Release - 26.08.0, sha256 9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08
        """.trimIndent()
        val out = r.text(input)
        listOf("192.168.1.113", "10.0.0.5", "0123456789abcdef0123456789abcdef", "eyJhbGci", "abc123", "hunter2", "12345678", "01P00A123456789",
            "sk-live", "ghp_", "workshop-u1.local", "fd7a:115c", "fe80::", "3c:22:fb", "jj@example.com", "jjennison", "/Users/jj", "James",
            "/storage/emulated/0", "com.nozzleitall.app", "b3BlbnNzaC1rZXkt", "Workshop U1", "/webcam", "utm=1").forEach { assertFalse("still contains $it:\n$out", out.contains(it)) }
        assertTrue(out, out.contains("[camera-url]"))
        assertTrue(out, out.contains("https://docs.opencentauri.cc/klipper-conversion?[query-removed]"))
        // Versions and digests are left alone.
        listOf("1.6.0.267_20260815150420", "26.08.0", "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08").forEach { assertTrue(it, out.contains(it)) }
        assertEquals(emptyList<String>(), r.leaks(out))
        assertTrue(r.leaks(input).isNotEmpty())
    }

    @Test fun sensitiveJsonFieldsAreDroppedWholesale() {
        val r = Redactor()
        val o = r.json(JSONObject().put("apiKey", "anything").put("address", "printer.example.com").put("nested", JSONObject().put("Access-Code", "x").put("ok", "fine"))) as JSONObject
        assertEquals("[redacted]", o.getString("apiKey"))
        assertEquals("[redacted]", o.getString("address"))
        assertEquals("[redacted]", o.getJSONObject("nested").getString("Access-Code"))
        assertEquals("fine", o.getJSONObject("nested").getString("ok"))
    }

    @Test fun jpegMetadataIsStripped() {
        val jpeg = ByteArrayOutputStream().apply {
            write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
            val exif = "Exif\u0000\u0000GPSLatitude 45.5 Owner jj".toByteArray(Charsets.ISO_8859_1)
            write(byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0, (exif.size + 2).toByte())); write(exif)
            val com = "shot by jj".toByteArray()
            write(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0, (com.size + 2).toByte())); write(com)
            write(byteArrayOf(0xFF.toByte(), 0xDB.toByte(), 0, 4, 1, 2))
            write(byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 4, 9, 9, 7, 7, 0xFF.toByte(), 0xD9.toByte()))
        }.toByteArray()
        val clean = Attachments.sanitize(jpeg, "image/jpeg")
        assertTrue(clean.removedMetadata)
        val s = String(clean.bytes, Charsets.ISO_8859_1)
        assertFalse(s.contains("GPS")); assertFalse(s.contains("jj"))
        assertEquals("image/jpeg", Attachments.sniff(clean.bytes))
        try { Attachments.sanitize("not an image".toByteArray(), "image/gif"); org.junit.Assert.fail() } catch (e: IllegalArgumentException) {}
    }

    private fun rezip(files: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { bos ->
        ZipOutputStream(bos).use { z -> files.forEach { (k, v) -> z.putNextEntry(ZipEntry(k)); z.write(v); z.closeEntry() } }
    }.toByteArray()

    private fun invalid(zip: ByteArray): List<String> = (BundleReader.read(zip) as? BundleReader.Result.Invalid)?.reasons ?: error("expected invalid")

    /** Re-seals a modified evidence.json with a fresh, internally consistent integrity manifest. */
    private fun reseal(files: Map<String, ByteArray>, ev: JSONObject): ByteArray {
        val content = (files - EvidenceBundle.INTEGRITY) + (EvidenceBundle.EVIDENCE to Canon.bytes(ev))
        return rezip(content + (EvidenceBundle.INTEGRITY to Canon.bytes(EvidenceBundle.integrityFor(content, ev))))
    }

    @Test fun corruptTamperedAndIncompleteBundlesAreRejected() {
        val (_, b) = run()
        val f = b.files
        assertTrue(invalid(b.zip().copyOf(200)).isNotEmpty())
        assertTrue(invalid("not a zip".toByteArray()).isNotEmpty())
        val tampered = f.toMutableMap().also { m -> m[EvidenceBundle.EVIDENCE] = String(m.getValue(EvidenceBundle.EVIDENCE)).replace("\"FAIL\"", "\"PASS\"").replace("\"UNVERIFIED\"", "\"PASS\"").toByteArray() }
        assertTrue(invalid(rezip(tampered)).any { it.contains("Hash mismatch") })
        assertTrue(invalid(rezip(f - EvidenceBundle.LOG)).any { it.contains("Listed file missing") })
        assertTrue(invalid(rezip(f + ("extra.txt" to "hi".toByteArray()))).any { it.contains("not covered") })
        assertTrue(invalid(rezip(f - EvidenceBundle.INTEGRITY)).any { it.contains("integrity.json is missing") })
        assertTrue(invalid(rezip(f + ("../escape.txt" to ByteArray(1)))).any { it.contains("Unsafe path") })
        val ev = b.evidence
        assertTrue(invalid(reseal(f, JSONObject(ev.toString()).also { it.getJSONObject("run").put("completedAt", JSONObject.NULL) })).any { it.contains("not completed") })
        assertTrue(invalid(reseal(f, JSONObject(ev.toString()).also { it.put("tests", JSONArray()) })).any { it.contains("no test results") })
        assertTrue(invalid(reseal(f, JSONObject(ev.toString()).also { it.put("version", JSONArray().put(2).put(0)) })).any { it.contains("Unsupported evidence format version 2") })
        // A bundle whose digest was edited in place doesn't verify either.
        val integ = JSONObject(String(f.getValue(EvidenceBundle.INTEGRITY))).put("contentDigest", "0".repeat(64))
        assertTrue(invalid(rezip(f + (EvidenceBundle.INTEGRITY to Canon.bytes(integ)))).any { it.contains("Content digest mismatch") })
    }

    @Test fun exportIsRefusedWhileAnOutcomeIsUnknownOrTheRunIsUnfinished() {
        val clock = Support.Clock()
        val st = Support.start("paxx-u1", SimulatedPrinter(SimulatedPrinter.Preset.PAXX_U1, clock::now), clock)
        st.session.proceed()
        try { Support.build(st); org.junit.Assert.fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("Finish the run")) }
    }

    @Test fun theLeakGateFlagsWhatRulesMissOrWereNotApplied() {
        // The final scan runs over the exported text; it catches a known private value or an address even if a rule was bypassed.
        assertTrue(Redactor(listOf("zz-unique-private-zz")).leaks("value zz-unique-private-zz here").isNotEmpty())
        assertTrue(Redactor().leaks("printer at 192.168.0.9").isNotEmpty())
        assertTrue(Redactor().leaks("clean text, sha256 9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08").isEmpty())
    }

    @Test fun canonicalJsonIsStable() {
        val a = JSONObject().put("b", 1.0).put("a", JSONArray().put("x\ny").put(0.1).put(-0.0)).put("c", JSONObject.NULL)
        assertEquals("{\n  \"a\": [\n    \"x\\ny\",\n    0.1,\n    0\n  ],\n  \"b\": 1,\n  \"c\": null\n}\n", Canon.write(a))
        assertEquals(Canon.write(a), Canon.write(JSONObject(Canon.write(a))))
        assertEquals(Canon.contentDigest(JSONObject().put("x", 1).put("runId", "a").put("startedAt", 5)), Canon.contentDigest(JSONObject().put("x", 1).put("runId", "b")))
    }
}
