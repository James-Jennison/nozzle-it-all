package net.jamesjennison.klippercompanion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Snapmaker U1/PAXX only (PrinterKind.SNAPMAKER_U1_PAXX): pairs with the printer's Bespok3d
 * daemon, browses/installs its signed plugin catalog, and mirrors the printer's own touchscreen
 * once HelixScreen is installed. Follows HeaterPanel.kt's fetch -> review -> confirm shape:
 * every mutating action (SSH enrollment, plugin install) requires a fresh review before it can be
 * confirmed; read-only actions (probe, preflight, status, catalog fetch) just show a notice.
 */
@Composable fun Bespok3dPanel(state: ScreenState, close: () -> Unit,
    factory: (String) -> Bespok3dReader = { a -> state.moonrakerFor(a) }) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val secrets = remember { CredentialStore.open(context) }
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    val reader = remember(state.address, state.generation) { factory(state.address) }
    var connection by remember(state.address) { mutableStateOf(Bespok3dConnectionStore.load(secrets, state.address)) }
    var notice by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var epoch by remember { mutableIntStateOf(0) }
    fun invalidate() { epoch++; notice = "" }
    DisposableEffect(reader, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (event == Lifecycle.Event.ON_STOP) { epoch++; job?.cancel(); reader.close(); notice = ""; busy = false }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); job?.cancel(); reader.close() }
    }
    fun <T> run(onSuccess: (T) -> Unit, action: () -> T) {
        invalidate(); busy = true; val ticket = epoch
        job = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { action() }
                ensureActive(); if (ticket != epoch) return@launch
                onSuccess(result)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { if (ticket == epoch) notice = e.message ?: "Bespok3d request failed." }
            finally { if (ticket == epoch) busy = false }
        }
    }
    // ---- pairing / preflight / enrollment (only shown before a daemon is paired) ----
    var probe by remember { mutableStateOf<Bespok3dProbe?>(null) }
    var sshPassword by remember { mutableStateOf("") }
    var label by remember(state.address) { mutableStateOf(state.profiles.firstOrNull { it.address == state.address }?.label.orEmpty().ifBlank { "Nozzle It All" }) }
    var preflightResult by remember { mutableStateOf<Bespok3dU1PreflightResult?>(null) }
    var offeredHostKey by remember(state.address) { mutableStateOf<String?>(null) }
    var trustedHostKey by remember(state.address) { mutableStateOf<String?>(null) }
    var enrollPending by remember { mutableStateOf(false) }
    var preparedAt by remember { mutableLongStateOf(0) }
    // ---- paired daemon: status + plugins ----
    var status by remember { mutableStateOf<Bespok3dStatus?>(null) }
    var catalog by remember { mutableStateOf<Bespok3dPluginCatalog?>(null) }
    var selectedPlugins by remember { mutableStateOf(setOf<String>()) }
    var installPending by remember { mutableStateOf(false) }
    val enabled = foreground && !busy
    val screenAvailable = state.catalog.cameras.any { it.isBespok3dScreen() }
    AlertDialog(onDismissRequest = close, title = { Text("Bespok3d (Snapmaker U1/PAXX)") }, confirmButton = { TextButton(close) { Text("Close") } }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Printer: ${state.address}")
            if (connection == null) {
                Text("Pairs this app with the Bespok3d daemon running on the printer. Enrolling a stock U1 installs the daemon over SSH first - this changes the printer's filesystem.")
                Button({ run({ result: Bespok3dProbe -> probe = result; notice = "Bespok3d ${result.version} detected." }) { reader.bespok3dProbe() } }, enabled = enabled, modifier = Modifier.testTag("bespok3d-probe")) { Text(if (busy) "Checking…" else "Probe daemon") }
                probe?.let { p -> Text("Daemon ${p.version} · fingerprint ${p.certificateSha256}", style = MaterialTheme.typography.bodySmall) }
                Text("Stock U1 enrollment (SSH)", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(sshPassword, { sshPassword = it; invalidate() }, enabled = !busy, label = { Text("root SSH password") }, singleLine = true, modifier = Modifier.testTag("bespok3d-ssh-password"))
                OutlinedTextField(label, { label = it.take(64); invalidate() }, enabled = !busy, label = { Text("Enrollment label") }, singleLine = true)
                // The password is only ever sent to a printer whose SSH host key you have confirmed: reading the key sends nothing.
                OutlinedButton({
                    val host = Moonraker.parseAddress(state.address).host
                    trustedHostKey = null
                    run({ fingerprint: String -> offeredHostKey = fingerprint; notice = "The printer offers SSH host key $fingerprint. Trust it only if you expect this printer." }) { Bespok3dU1EnrollmentService().hostKey(host) }
                }, enabled = enabled, modifier = Modifier.testTag("bespok3d-read-hostkey")) { Text(if (busy) "Reading…" else "Read SSH host key") }
                offeredHostKey?.let { offered ->
                    Text(offered, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("bespok3d-hostkey"))
                    if (trustedHostKey != offered) OutlinedButton({ trustedHostKey = offered }, enabled = enabled, modifier = Modifier.testTag("bespok3d-trust-hostkey")) { Text("Trust this host key") }
                    else Text("Host key trusted: the password will only be sent to a printer presenting it.", style = MaterialTheme.typography.bodySmall)
                }
                Button({
                    val host = Moonraker.parseAddress(state.address).host
                    val trusted = trustedHostKey ?: return@Button
                    run({ result: Bespok3dU1PreflightResult -> preflightResult = result; notice = if (result.eligible) "Eligible for enrollment. SSH host key ${result.sshHostKeySha256}." else result.reason ?: "Not eligible." }) {
                        Bespok3dU1EnrollmentService().preflight(host, sshPassword, trusted)
                    }
                }, enabled = enabled && sshPassword.isNotEmpty() && trustedHostKey != null, modifier = Modifier.testTag("bespok3d-preflight")) { Text(if (busy) "Checking…" else "Run SSH preflight") }
                preflightResult?.let { pf ->
                    if (pf.eligible) {
                        Button({ enrollPending = true; preparedAt = System.nanoTime() / 1_000_000 }, enabled = enabled, modifier = Modifier.testTag("bespok3d-review-enroll")) { Text("Review enrollment") }
                    }
                }
                if (enrollPending) {
                    Text("This installs the signed Bespok3d daemon on the printer over SSH, using the fingerprint confirmed above. Stay connected to the printer during enrollment.")
                    Button({
                        val pf = preflightResult
                        if (pf == null || System.nanoTime() / 1_000_000 - preparedAt !in 0..60_000) { notice = "Review preflight again; this confirmation expired."; enrollPending = false }
                        else {
                            enrollPending = false
                            val host = Moonraker.parseAddress(state.address).host
                            val credentials = Bespok3dU1EnrollmentCredentials("helix-${java.util.UUID.randomUUID()}",
                                ByteArray(32).also(java.security.SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) })
                            run({ result: Bespok3dU1EnrollmentResult ->
                                val paired = Bespok3dConnection(credentials.identity, credentials.token, result.certificatePem)
                                Bespok3dConnectionStore.save(secrets, state.address, paired)
                                connection = paired
                                notice = "Enrolled Bespok3d ${result.daemonVersion} (jinni ${result.jinniVersion})."
                            }) {
                                val bootstrap = Bespok3dBootstrapPackages.load(context)
                                Bespok3dU1EnrollmentService().enroll(Bespok3dU1EnrollmentConfig(host, sshPassword, pf.sshHostKeySha256, label.trim(), credentials), bootstrap)
                            }
                        }
                    }, enabled = enabled, modifier = Modifier.testTag("bespok3d-confirm-enroll")) { Text("Confirm enrollment") }
                }
            } else {
                val paired = connection!!
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) { Text("Paired with this printer's Bespok3d daemon.") } }
                Button({ run({ result: Bespok3dStatus? -> status = result; notice = if (result == null) "Pairing is still pending approval on the printer." else "Bespok3d ${result.version} · printer ${result.printerUuid}." }) { reader.bespok3dStatus(paired) } }, enabled = enabled, modifier = Modifier.testTag("bespok3d-status")) { Text(if (busy) "Checking…" else "Check status") }
                Button({ run({ result: Bespok3dPluginCatalog -> catalog = result; notice = "${result.plugins.size} plugins available, ${result.installed.size} installed." }) { reader.bespok3dPlugins(paired) } }, enabled = enabled, modifier = Modifier.testTag("bespok3d-load-plugins")) { Text(if (busy) "Loading…" else "Load plugin catalog") }
                catalog?.plugins?.forEach { plugin ->
                    val installedVersion = catalog?.installed?.get(plugin.id)
                    Card(Modifier.fillMaxWidth()) { FlowRow(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(plugin.id in selectedPlugins, { selectedPlugins = if (plugin.id in selectedPlugins) selectedPlugins - plugin.id else selectedPlugins + plugin.id; invalidate() },
                            enabled = !busy && installedVersion == null, label = { Text(plugin.title) }, modifier = Modifier.testTag("bespok3d-plugin-${plugin.id}"))
                        Text(if (installedVersion != null) "Installed $installedVersion" else plugin.version, style = MaterialTheme.typography.bodySmall)
                    } }
                }
                if (selectedPlugins.isNotEmpty()) {
                    Button({ installPending = true; preparedAt = System.nanoTime() / 1_000_000 }, enabled = enabled, modifier = Modifier.testTag("bespok3d-review-install")) { Text("Review plugin install") }
                }
                if (installPending) {
                    Text("Installs: ${selectedPlugins.joinToString()}")
                    Button({
                        if (System.nanoTime() / 1_000_000 - preparedAt !in 0..60_000) { notice = "Review the install again; this confirmation expired."; installPending = false }
                        else {
                            installPending = false
                            run({ result: Bespok3dPluginInstallResult -> notice = if (result.ok) "Installed: ${result.installedIds.joinToString()}." else "Failed: ${result.failures}"; selectedPlugins = emptySet() }) {
                                reader.bespok3dInstallPlugins(paired, selectedPlugins.toList())
                            }
                        }
                    }, enabled = enabled, modifier = Modifier.testTag("bespok3d-confirm-install")) { Text("Confirm plugin install") }
                }
                TextButton({ Bespok3dConnectionStore.clear(secrets, state.address); connection = null; status = null; catalog = null; notice = "Forgot this printer's Bespok3d pairing." }, enabled = !busy) { Text("Forget pairing") }
            }
            if (screenAvailable) {
                Text("Remote screen", style = MaterialTheme.typography.labelLarge)
                Text("Mirrors the printer's own touchscreen. Taps are forwarded; this is disabled while printing.", style = MaterialTheme.typography.bodySmall)
                KilnFrame { Bespok3dScreenMirror(state.address) }
            }
            if (notice.isNotEmpty()) Text(notice, modifier = Modifier.testTag("bespok3d-notice"))
        }
    })
}

data class Bespok3dScreenInfo(val width: Int, val height: Int, val touchOk: Boolean, val printState: String)

/** Same host as the Moonraker connection: an http:// connection resets to the default port
 * (80, where the printer's nginx/helixd screen proxy lives - Moonraker itself is on :7125);
 * an https:// tunnel/Tailscale Serve address keeps its own port, which already routes by path.
 * Mirrors Helix's printerProxyOrigin/resolveScreenApiUrl. */
internal fun bespok3dScreenOrigin(address: String): HttpUrl {
    val base = Moonraker.parseAddress(address)
    return (if (base.scheme == "http") base.newBuilder().port(80) else base.newBuilder()).encodedPath("/").build()
}

/**
 * Polls the printer's touchscreen as single JPEG snapshots (there is no MJPEG stream or WebView
 * involved - see the investigation note in the Phase 1 port report) and forwards taps. Frames are
 * requested back-to-back rather than on a fixed timer, same rationale as Helix's useScreenMirror.
 */
@Composable fun Bespok3dScreenMirror(address: String, modifier: Modifier = Modifier.fillMaxWidth().aspectRatio(5f / 3f)) {
    val origin = remember(address) { runCatching { bespok3dScreenOrigin(address) }.getOrNull() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var active by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_START) active = true else if (e == Lifecycle.Event.ON_STOP) active = false }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    val client = remember { OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).retryOnConnectionFailure(false).build() }
    DisposableEffect(origin) { onDispose { client.dispatcher.cancelAll(); client.connectionPool.evictAll() } }
    var bitmap by remember(origin) { mutableStateOf<Bitmap?>(null) }
    var info by remember(origin) { mutableStateOf<Bespok3dScreenInfo?>(null) }
    var label by remember(origin) { mutableStateOf("Connecting to remote screen…") }
    LaunchedEffect(origin, active) {
        val base = origin ?: run { label = "Unsupported printer address."; return@LaunchedEffect }
        if (!active) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            while (isActive) {
                try {
                    client.newCall(Request.Builder().url(base.newBuilder().addPathSegments("api/screen/info").build()).build()).execute().use { response ->
                        if (response.isSuccessful) response.body?.string()?.let { body ->
                            val json = JSONObject(body)
                            val parsed = Bespok3dScreenInfo(json.optInt("width", 800), json.optInt("height", 480), json.optBoolean("touch_ok", true), json.optString("print_state", "standby"))
                            withContext(Dispatchers.Main) { info = parsed }
                        }
                    }
                    val snapshotUrl = base.newBuilder().addPathSegments("api/screen/snapshot").addQueryParameter("q", "60").addQueryParameter("t", System.nanoTime().toString()).build()
                    client.newCall(Request.Builder().url(snapshotUrl).header("Cache-Control", "no-cache").build()).execute().use { response ->
                        if (!response.isSuccessful) throw ApiFailure("Remote screen unavailable (HTTP ${response.code}).")
                        val bytes = response.body?.bytes() ?: throw ApiFailure("Remote screen returned no frame.")
                        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                        if (opts.outWidth !in 1..8192 || opts.outHeight !in 1..8192) throw ApiFailure("Unsupported remote-screen frame.")
                        opts.inJustDecodeBounds = false
                        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: throw ApiFailure("Invalid remote-screen frame.")
                        withContext(Dispatchers.Main) { bitmap = decoded; label = "Remote screen · ${info?.printState ?: "live"}" }
                    }
                } catch (e: CancellationException) { throw e } catch (_: Exception) {
                    withContext(Dispatchers.Main) { label = "Remote screen unavailable. Retrying…" }
                    delay(1500)
                }
            }
        }
    }
    val tapScope = rememberCoroutineScope()
    Box(modifier) {
        bitmap?.let { bmp ->
            Image(bmp.asImageBitmap(), "Printer touchscreen", Modifier.fillMaxSize().pointerInput(origin, info) {
                detectTapGestures { offset ->
                    val target = origin ?: return@detectTapGestures
                    val dims = info ?: return@detectTapGestures
                    if (!dims.touchOk) return@detectTapGestures
                    val x = (offset.x / size.width * dims.width).toInt().coerceIn(0, dims.width - 1)
                    val y = (offset.y / size.height * dims.height).toInt().coerceIn(0, dims.height - 1)
                    tapScope.launch(Dispatchers.IO) {
                        runCatching {
                            val body = JSONObject().put("x", x).put("y", y).put("force", false).toString().toRequestBody("application/json".toMediaType())
                            client.newCall(Request.Builder().url(target.newBuilder().addPathSegments("api/screen/tap").build()).post(body).build()).execute().close()
                        }
                    }
                }
            }, contentScale = ContentScale.Fit)
        }
    }
    Text(label, style = MaterialTheme.typography.bodySmall)
}
