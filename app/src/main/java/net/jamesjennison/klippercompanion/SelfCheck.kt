package net.jamesjennison.klippercompanion

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.jamesjennison.klippercompanion.project.AppDatabase
import okhttp3.OkHttpClient
import okhttp3.Request
import org.orcaslicer.engine.NativeEngine
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Exercises every reflection-heavy dependency against loopback and local data only - nothing leaves the device and
 * no printer is contacted. A check that ends in a clean, expected failure (connection refused) passes; a linkage
 * error (missing class or method, failed native link) fails. It exists so the minified/obfuscated release build can
 * be verified without printers, and doubles as a support diagnostic.
 */
data class SelfCheckResult(val name: String, val ok: Boolean, val detail: String)

object SelfCheck {
    private suspend fun check(name: String, block: suspend () -> String): SelfCheckResult =
        try { SelfCheckResult(name, true, block()) }
        catch (e: LinkageError) { SelfCheckResult(name, false, "${e.javaClass.simpleName}: ${e.message}") }
        catch (e: ExceptionInInitializerError) { SelfCheckResult(name, false, "init failed: ${rootOf(e)}") }
        catch (e: Exception) { SelfCheckResult(name, false, "${e.javaClass.simpleName}: ${e.message}") }

    private fun rootOf(t: Throwable): String { val r = generateSequence(t) { it.cause }.last(); return "${r.javaClass.name}: ${r.message}" }

    /** Runs [block], which must fail with an ordinary exception (not a linkage error); returns what it failed with. */
    private suspend fun failsCleanly(block: suspend () -> Unit): String {
        try { block() } catch (e: LinkageError) { throw e } catch (e: ExceptionInInitializerError) { throw e } catch (e: Exception) {
            val root = generateSequence<Throwable>(e) { it.cause }.last()
            if (root is LinkageError) throw root
            return "failed cleanly (${root.javaClass.simpleName})"
        }
        return "unexpectedly succeeded"
    }

    suspend fun run(context: Context): List<SelfCheckResult> = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        listOf(
            check("Slicing engine") { "engine ${NativeEngine.nativeGetVersion()}" },
            check("Project database (Room)") { AppDatabase.get(app).projectDao().getProject("self-check-none"); "opened, queried" },
            check("Encrypted credentials (Tink)") {
                val prefs = CredentialStore.open(app)
                prefs.edit().putString("self-check", "ok").commit(); val back = prefs.getString("self-check", null); prefs.edit().remove("self-check").commit()
                check(back == "ok") { "round trip returned $back" }; "encrypted write/read round trip"
            },
            check("Signed Bespok3d packages (OpenPGP)") { val set = Bespok3dBootstrapPackages.load(app); "verified and loaded ${set.javaClass.simpleName}" },
            check("SSH keys (jsch)") { val kp = KeyPair.genKeyPair(JSch(), KeyPair.RSA, 2048); val size = kp.keySize; kp.dispose(); "generated RSA-$size" },
            check("HTTP client (OkHttp)") {
                failsCleanly { OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build().newCall(Request.Builder().url("http://127.0.0.1:1/").build()).execute().close() }
            },
            check("Bambu MQTT/TLS client (HiveMQ, Netty)") {
                failsCleanly { BambuStatusProbe().probe(BambuStatusProbeConfig("127.0.0.1", "00M00A000000000", "00000000")).get(8, TimeUnit.SECONDS) }
            },
            check("Bambu FTPS client (commons-net, BouncyCastle TLS)") {
                val tmp = File(app.cacheDir, "self-check.gcode.3mf").also { it.writeText("self-check") } // non-empty, so validation passes and the TLS connect path runs
                try { failsCleanly { BambuFtpsClient().upload(BambuFtpsConfig("127.0.0.1", "00M00A000000000", "00000000", 1500, 1500), tmp) } } finally { tmp.delete() }
            },
            check("Home-screen widget (Glance)") { "${GlanceAppWidgetManager(app).getGlanceIds(NozzlePrinterWidget::class.java).size} widget(s) placed" },
        )
    }
}
