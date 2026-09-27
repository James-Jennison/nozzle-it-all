package com.nozzleitall.stocku1

import com.nozzleitall.printer.external.AdapterAccountState
import com.sun.net.httpserver.HttpServer
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files

class StockU1AdapterTest {
    /** A local stand-in for the Snapmaker account endpoint. */
    private class FakeAccountHost(var mode: String = "ok") : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/api/common/accounts/current") { ex ->
                val auth = ex.requestHeaders.getFirst("Authorization")
                val code = when { mode == "down" -> 503; mode == "revoked" -> 401; auth == "good-token" -> 200; else -> 401 }
                val body = """{"code":0}""".toByteArray()
                ex.sendResponseHeaders(code, body.size.toLong()); ex.responseBody.use { it.write(body) }
            }
            start()
        }
        val url get() = "http://127.0.0.1:${server.address.port}"
        override fun close() = server.stop(0)
    }

    private fun dir() = Files.createTempDirectory("stock").toFile().apply { deleteOnExit() }

    @Test fun signInWithPastedCallbackJsonStoresAPrivateToken() = FakeAccountHost().use { host ->
        val d = dir()
        val a = SnapmakerAccount(d, host.url, "https://example.invalid/signin")
        assertEquals(AdapterAccountState.SIGNED_OUT, a.account().state)
        assertEquals(AdapterAccountState.SIGNING_IN, a.beginSignIn().state)
        assertEquals(AdapterAccountState.SIGNED_IN, a.completeSignIn("""{"access_token":"good-token","expires_in":3600}""").state)
        val f = File(d, "account.json")
        assertTrue(f.exists())
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(f.toPath())))
        assertEquals(AdapterAccountState.SIGNED_OUT, a.signOut().state)
        assertFalse(f.exists())
    }

    @Test fun rejectedTokenIsNotSaved() = FakeAccountHost().use { host ->
        val d = dir()
        val a = SnapmakerAccount(d, host.url)
        assertEquals(AdapterAccountState.SIGNED_OUT, a.completeSignIn("bad-token").state)
        assertFalse(File(d, "account.json").exists())
    }

    @Test fun expiredTokenOnlyChangesTheStockAccountState() = FakeAccountHost().use { host ->
        val a = SnapmakerAccount(dir(), host.url)
        a.completeSignIn("good-token")
        host.mode = "revoked" // any token now answers 401
        assertEquals(AdapterAccountState.EXPIRED, a.refresh().state)
    }

    @Test fun cloudOutageDoesNotSignTheUserOut() = FakeAccountHost().use { host ->
        val a = SnapmakerAccount(dir(), host.url)
        a.completeSignIn("good-token")
        host.mode = "down"
        assertEquals(AdapterAccountState.SIGNED_IN, a.refresh().state)
        val unreachable = SnapmakerAccount(dir(), "http://127.0.0.1:9") // nothing listens on the discard port
        assertEquals(AdapterAccountState.UNAVAILABLE, unreachable.completeSignIn("good-token").state)
    }
}
