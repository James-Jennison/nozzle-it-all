package com.nozzleitall.adapter.elegoo

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The few MQTT 3.1.1 packets the Centauri Carbon 2 needs (OASIS MQTT 3.1.1, sections 2-3): CONNECT/CONNACK,
 * SUBSCRIBE/SUBACK, PUBLISH (QoS 0 and 1) with PUBACK, PINGREQ/PINGRESP and DISCONNECT. elegoo-link uses Eclipse Paho
 * (src/lan/protocols/mqtt_protocol.cpp: clean session, 60 s keep-alive, QoS 1 publishes and subscriptions); a full
 * client library would add a large dependency for a single plaintext LAN broker, so this is the minimum, with bounded
 * packet sizes and no automatic reconnect or redelivery.
 */
internal object MqttCodec {
    const val CONNECT = 1; const val CONNACK = 2; const val PUBLISH = 3; const val PUBACK = 4
    const val SUBSCRIBE = 8; const val SUBACK = 9; const val PINGREQ = 12; const val PINGRESP = 13; const val DISCONNECT = 14

    class Packet(val type: Int, val flags: Int, val body: ByteArray)
    class Publish(val topic: String, val packetId: Int?, val qos: Int, val payload: ByteArray)

    /** Reads one packet; null at a clean end of stream. Packets larger than [max] end the connection. */
    fun read(input: InputStream, max: Int): Packet? {
        val first = input.read()
        if (first < 0) return null
        var length = 0; var multiplier = 1; var i = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException("The connection closed mid-packet.")
            length += (b and 0x7F) * multiplier
            if (b and 0x80 == 0) break
            multiplier *= 128
            if (++i >= 4) throw IOException("Malformed packet length.")
        }
        if (length > max) throw IOException("The printer sent a packet larger than $max bytes.")
        val body = ByteArray(length)
        var off = 0
        while (off < length) { val n = input.read(body, off, length - off); if (n < 0) throw EOFException("The connection closed mid-packet."); off += n }
        return Packet(first ushr 4, first and 0x0F, body)
    }

    fun encode(type: Int, flags: Int, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(body.size + 5)
        out.write((type shl 4) or (flags and 0x0F))
        var len = body.size
        do { var b = len % 128; len /= 128; if (len > 0) b = b or 0x80; out.write(b) } while (len > 0)
        out.write(body)
        return out.toByteArray()
    }

    fun str(s: String): ByteArray = s.toByteArray(Charsets.UTF_8).let { b -> byteArrayOf((b.size shr 8).toByte(), b.size.toByte()) + b }

    fun connect(clientId: String, user: String?, password: String?, keepAliveSeconds: Int): ByteArray {
        var flags = 0x02 // clean session
        if (user != null) flags = flags or 0x80
        if (password != null) flags = flags or 0x40
        val out = ByteArrayOutputStream()
        out.write(str("MQTT")); out.write(4); out.write(flags); out.write(keepAliveSeconds shr 8); out.write(keepAliveSeconds and 0xFF)
        out.write(str(clientId)); user?.let { out.write(str(it)) }; password?.let { out.write(str(it)) }
        return encode(CONNECT, 0, out.toByteArray())
    }

    fun subscribe(packetId: Int, topics: List<String>, qos: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(packetId shr 8); out.write(packetId and 0xFF)
        topics.forEach { out.write(str(it)); out.write(qos) }
        return encode(SUBSCRIBE, 0x02, out.toByteArray())
    }

    fun publish(topic: String, payload: ByteArray, qos: Int, packetId: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(str(topic))
        if (qos > 0) { out.write(packetId shr 8); out.write(packetId and 0xFF) }
        out.write(payload)
        return encode(PUBLISH, qos shl 1, out.toByteArray())
    }

    fun puback(packetId: Int): ByteArray = encode(PUBACK, 0, byteArrayOf((packetId shr 8).toByte(), packetId.toByte()))

    fun parsePublish(p: Packet): Publish {
        val b = p.body
        require(b.size >= 2) { "Short PUBLISH." }
        val tl = ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
        require(b.size >= 2 + tl) { "Short PUBLISH." }
        val topic = String(b, 2, tl, Charsets.UTF_8)
        val qos = (p.flags shr 1) and 0x03
        var off = 2 + tl
        val id = if (qos > 0) { require(b.size >= off + 2) { "Short PUBLISH." }; (((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)).also { off += 2 } } else null
        return Publish(topic, id, qos, b.copyOfRange(off, b.size))
    }
}

class MqttRefused(message: String) : IOException(message)

/** One MQTT connection to one printer. Messages arrive on a reader thread through [onMessage]; [onLost] runs once. */
internal class MiniMqttClient(
    private val host: String, private val port: Int, private val clientId: String, private val user: String?, private val password: String?,
    private val keepAliveSeconds: Int = 60, private val onMessage: (String, ByteArray) -> Unit, private val onLost: (String) -> Unit,
) : AutoCloseable {
    private val socket = Socket()
    private lateinit var output: OutputStream
    private val ids = AtomicInteger(1)
    @Volatile var alive = false; private set
    @Volatile private var subAck: CountDownLatch? = null
    @Volatile private var closing = false

    fun connect(timeoutMs: Int) {
        socket.connect(InetSocketAddress(host, port), timeoutMs)
        socket.soTimeout = timeoutMs
        socket.tcpNoDelay = true
        val input = BufferedInputStream(socket.getInputStream())
        output = socket.getOutputStream()
        write(MqttCodec.connect(clientId, user, password, keepAliveSeconds))
        val ack = MqttCodec.read(input, 16) ?: throw IOException("The printer closed the connection.")
        if (ack.type != MqttCodec.CONNACK || ack.body.size < 2) throw IOException("The printer's reply was not understood.")
        when (val rc = ack.body[1].toInt() and 0xFF) {
            0 -> {}
            4, 5 -> throw MqttRefused("The printer rejected the access code.")
            else -> throw MqttRefused("The printer refused the connection (code $rc).")
        }
        socket.soTimeout = 0
        alive = true
        Thread({ readLoop(input) }, "elegoo-mqtt-$host").apply { isDaemon = true; start() }
    }

    private fun readLoop(input: InputStream) {
        var reason = "The connection to the printer was lost."
        try {
            while (true) {
                val p = MqttCodec.read(input, ElegooNet.MAX_MESSAGE_BYTES) ?: break
                when (p.type) {
                    MqttCodec.PUBLISH -> {
                        val msg = MqttCodec.parsePublish(p)
                        if (msg.qos == 1 && msg.packetId != null) write(MqttCodec.puback(msg.packetId))
                        runCatching { onMessage(msg.topic, msg.payload) }
                    }
                    MqttCodec.SUBACK -> subAck?.countDown()
                    else -> {} // PUBACK, PINGRESP: nothing to do.
                }
            }
        } catch (e: Exception) { if (!closing) reason = e.message ?: reason }
        finally {
            alive = false
            runCatching { socket.close() }
            if (!closing) onLost(reason)
        }
    }

    @Synchronized private fun write(bytes: ByteArray) { output.write(bytes); output.flush() }

    fun subscribe(topics: List<String>, qos: Int, timeoutMs: Long) {
        val latch = CountDownLatch(1).also { subAck = it }
        write(MqttCodec.subscribe(nextId(), topics, qos))
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) throw IOException("The printer didn't confirm the subscription.")
    }

    /** Writes a PUBLISH. Throws IOException if the connection failed while writing (the message may or may not have left). */
    fun publish(topic: String, payload: String, qos: Int = 1) {
        if (!alive) throw IOException("Not connected to the printer.")
        write(MqttCodec.publish(topic, payload.toByteArray(Charsets.UTF_8), qos, nextId()))
    }

    fun ping() { if (alive) runCatching { write(MqttCodec.encode(MqttCodec.PINGREQ, 0, ByteArray(0))) } }

    private fun nextId(): Int = ids.getAndUpdate { if (it >= 0xFFFF) 1 else it + 1 }

    override fun close() {
        closing = true
        if (alive) runCatching { write(MqttCodec.encode(MqttCodec.DISCONNECT, 0, ByteArray(0))) }
        alive = false
        runCatching { socket.close() }
    }
}
