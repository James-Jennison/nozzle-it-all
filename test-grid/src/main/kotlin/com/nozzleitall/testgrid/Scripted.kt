package com.nozzleitall.testgrid

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/**
 * Plays the operator for a simulated run: approves every step, answers observations from [answers] (step id to value)
 * or with the step's expected value, and attaches a generated placeholder photo (carrying fake location metadata, so
 * metadata removal is exercised). It refuses to drive anything but a simulated target: a physical printer is only ever
 * operated by a person approving each step in Test Mode.
 */
class ScriptedOperator(private val answers: Map<String, String> = emptyMap(), private val onEvent: (String) -> Unit = {}) {
    fun run(session: RunSession, target: TestTarget): Pending {
        require(target.description.kind == TargetKind.SIMULATED) { "The scripted operator only drives simulated printers." }
        var p = session.proceed()
        var guard = 0
        while (p !is Pending.Finished && guard++ < 10_000) {
            p = when (p) {
                is Pending.Preconditions -> { onEvent("preconditions ${p.test.id}: all confirmed (simulated)"); session.answerPreconditions(p.test.preconditions.associate { it.id to true }) }
                is Pending.Confirmation -> { onEvent("approve ${p.test.id}/${p.step.id}: ${p.action}"); session.approve(p.step.id) }
                is Pending.Observation -> { val v = answers[p.step.id] ?: defaultAnswer(p.step); onEvent("observe ${p.test.id}/${p.step.id}: $v"); session.observe(p.step.id, v, "Simulated run: no physical observation was made.") }
                is Pending.Attachment -> { onEvent("attach ${p.test.id}/${p.step.id}: placeholder PNG"); session.attach(p.step.id, placeholderPng(), "image/png") }
                is Pending.UnknownReview -> { onEvent("review unknown outcome of ${p.marker.action}"); session.reviewUnknown("Simulated review: status re-read after the lost reply."); session.proceed() }
                Pending.Finished -> p
            }
        }
        return p
    }

    companion object {
        fun defaultAnswer(step: Step): String = when (step.params.optString("response")) {
            "yes_no" -> step.expect.optString("equals", "yes")
            "pass_partial_fail" -> "pass"
            "number" -> {
                val min = step.expect.optDouble("min", Double.NaN); val max = step.expect.optDouble("max", Double.NaN)
                (if (min.isFinite() && max.isFinite()) (min + max) / 2 else if (min.isFinite()) min else if (max.isFinite()) max else 0.0).toString()
            }
            "choice" -> step.expect.strings("oneOf").firstOrNull() ?: step.params.strings("choices").first()
            else -> "Simulated run: no physical observation."
        }

        /** A 1 × 1 PNG with tEXt and eXIf chunks that sanitizing must remove. */
        fun placeholderPng(): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            fun chunk(type: String, data: ByteArray) {
                val len = data.size
                out.write(byteArrayOf((len ushr 24).toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte()))
                val td = type.toByteArray(Charsets.US_ASCII) + data
                out.write(td)
                val crc = CRC32().also { it.update(td) }.value
                out.write(byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte()))
            }
            chunk("IHDR", byteArrayOf(0, 0, 0, 1, 0, 0, 0, 1, 8, 0, 0, 0, 0))
            chunk("tEXt", "Comment\u0000GPS 45.5231,-122.6765 taken by jj at /home/jj/Pictures".toByteArray(Charsets.ISO_8859_1))
            chunk("eXIf", "MM\u0000*fake-exif-gps".toByteArray(Charsets.ISO_8859_1))
            chunk("IDAT", byteArrayOf(0x78, 0x9C.toByte(), 0x63, 0x60, 0x00, 0x00, 0x00, 0x02, 0x00, 0x01))
            chunk("IEND", ByteArray(0))
            return out.toByteArray()
        }
    }
}
