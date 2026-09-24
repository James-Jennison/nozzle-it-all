package net.jamesjennison.klippercompanion

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.jamesjennison.klippercompanion.project.ProjectDao

// Phase 10: Discover - browse MyMiniFactory's public catalogue, read each model's real license terms and credit line,
// and (signed in) download it straight into a new project. Everything shown is data from MyMiniFactory's API, parsed
// strictly (MyMiniFactory.kt); nothing is rendered as HTML.

/** The nozzleitall://mmf-auth sign-in redirect, handed from MainActivity to the Discover screen (consumed once). */
object MmfRedirects { var pending by mutableStateOf<String?>(null) }

private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

@OptIn(ExperimentalLayoutApi::class)
@Composable fun DiscoverScreen(
    settings: MmfSettings,
    dao: ProjectDao,
    signInRedirect: String?,
    onRedirectConsumed: () -> Unit,
    onStartSignIn: (String) -> Unit,
    onOpenProject: (String) -> Unit,
    apiFactory: (String) -> MmfApi = { MyMiniFactoryClient(it) },
    authFactory: (MmfSettings) -> MmfAuthManager? = { s -> s.clientKey()?.let { MmfAuthManager(s, MyMiniFactoryOAuth(it)) } },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var apiKey by remember { mutableStateOf(settings.apiKey()) }
    var clientKey by remember { mutableStateOf(settings.clientKey()) }
    val api = remember(apiKey) { apiKey?.let(apiFactory) }
    val auth = remember(clientKey) { authFactory(settings) }
    var signedIn by remember(auth) { mutableStateOf(auth?.isSignedIn() == true) }
    var keyDraft by remember { mutableStateOf("") }; var clientDraft by remember { mutableStateOf("") }; var setupError by remember { mutableStateOf<String?>(null) }

    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(MmfSort.POPULARITY) }
    var remix by remember { mutableStateOf(false) }; var commercial by remember { mutableStateOf(false) }; var supportFree by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<MmfObject>>(emptyList()) }; var total by remember { mutableStateOf(0) }; var page by remember { mutableStateOf(1) }
    var loading by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<MmfObject?>(null) }
    var accountMessage by remember { mutableStateOf<String?>(null) }

    fun search(reset: Boolean) {
        val a = api ?: return
        if (reset) { page = 1; results = emptyList() }
        loading = true; error = null
        val request = MmfSearch(query, page, 30, sort, remix, commercial, supportFree)
        scope.launch {
            try { val r = io { a.search(request) }; // Pages can overlap when the catalogue shifts between requests, so repeats are dropped (LazyColumn keys must be unique).
                results = (if (reset) r.items else results + r.items).distinctBy { it.id }; total = r.totalCount }
            catch (e: MmfException) { error = e.message } catch (e: Exception) { error = "Search failed: ${e.message}" }
            loading = false
        }
    }
    LaunchedEffect(api) { if (api != null && results.isEmpty()) search(true) }

    LaunchedEffect(signInRedirect) {
        val redirect = signInRedirect ?: return@LaunchedEffect
        val state = settings.pendingState()
        onRedirectConsumed()
        try {
            if (state == null) throw MmfAuthLinks.SignInFailed("No sign-in was in progress.")
            val (token, _) = MmfAuthLinks.parseRedirect(redirect, state)
            val device = MmfDeviceInfo(settings.deviceId(), android.os.Build.MANUFACTURER, android.os.Build.MODEL, java.util.Locale.getDefault().toLanguageTag(), "NozzleItAll/${BuildConfig.VERSION_NAME}")
            io { (auth ?: throw MmfException.NotConfigured()).completeSignIn(token, device) }
            signedIn = true; accountMessage = "Signed in to MyMiniFactory."
        } catch (e: MmfAuthLinks.SignInFailed) { accountMessage = e.message } catch (e: MmfException) { accountMessage = e.message }
        finally { settings.endSignIn() }
    }

    Box(Modifier.fillMaxSize().testTag("discover")) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Discover", style = MaterialTheme.typography.titleLarge)
            if (api == null) {
                SetupCard(keyDraft, { keyDraft = it }, setupError) {
                    try { settings.saveApiKey(keyDraft); apiKey = settings.apiKey(); keyDraft = ""; setupError = null } catch (e: IllegalArgumentException) { setupError = e.message }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(query, { query = it }, label = { Text("Search models") }, singleLine = true, modifier = Modifier.weight(1f).testTag("mmf-search"))
                    Button({ search(true) }, enabled = !loading, modifier = Modifier.testTag("mmf-search-go")) { Text("Search") }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MmfSort.entries.forEach { s -> FilterChip(sort == s, { sort = s; search(true) }, label = { Text(s.label) }, modifier = Modifier.testTag("mmf-sort-${s.code}")) }
                    FilterChip(remix, { remix = !remix; search(true) }, label = { Text("Remix OK") }, modifier = Modifier.testTag("mmf-filter-remix"))
                    FilterChip(commercial, { commercial = !commercial; search(true) }, label = { Text("Commercial OK") }, modifier = Modifier.testTag("mmf-filter-commercial"))
                    FilterChip(supportFree, { supportFree = !supportFree; search(true) }, label = { Text("No supports") }, modifier = Modifier.testTag("mmf-filter-support"))
                }
                AccountRow(signedIn, clientKey != null, clientDraft, { clientDraft = it }, accountMessage,
                    onSignIn = { val k = clientKey ?: return@AccountRow; onStartSignIn(MmfAuthLinks.authorizeUrl(k, settings.beginSignIn())) },
                    onSignOut = { auth?.signOut(); settings.clear(); signedIn = false; accountMessage = "Signed out." },
                    onSaveClient = { try { settings.saveClientKey(clientDraft); clientKey = settings.clientKey(); clientDraft = ""; accountMessage = null } catch (e: IllegalArgumentException) { accountMessage = e.message } })
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("mmf-error")) }
                if (loading && results.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("mmf-loading"))
                if (!loading && error == null && results.isEmpty()) Text("No models found.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("mmf-empty"))
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(results, key = { it.id }) { o -> ResultCard(o) { selected = o } }
                    if (results.isNotEmpty() && results.size < total) item { OutlinedButton({ page += 1; search(false) }, enabled = !loading, modifier = Modifier.fillMaxWidth().testTag("mmf-more")) { Text(if (loading) "Loading…" else "Load more (${results.size} of $total)") } }
                }
                Text("Models and images are provided by MyMiniFactory. Searches are sent to MyMiniFactory; nothing else leaves your device.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        selected?.let { obj ->
            if (api != null) ModelDetail(obj, api, signedIn, auth, dao, onClose = { selected = null }, onSignIn = { val k = clientKey; if (k != null) onStartSignIn(MmfAuthLinks.authorizeUrl(k, settings.beginSignIn())) },
                onOpenProject = { selected = null; onOpenProject(it) })
        }
    }
}

@Composable private fun SetupCard(draft: String, onDraft: (String) -> Unit, error: String?, onSave: () -> Unit) {
    Card(Modifier.fillMaxWidth().testTag("mmf-setup")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Connect MyMiniFactory", style = MaterialTheme.typography.titleMedium)
            Text("Discover browses MyMiniFactory's catalogue through its public API, which needs a developer API key. Create a client in your MyMiniFactory account settings (myminifactory.com/pages/for-developers) and paste its key here. It is stored encrypted on this device.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(draft, onDraft, label = { Text("API key") }, singleLine = true, isError = error != null, modifier = Modifier.fillMaxWidth().testTag("mmf-key-field"))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("mmf-key-error")) }
            Button(onSave, enabled = draft.isNotBlank(), modifier = Modifier.testTag("mmf-key-save")) { Text("Save key") }
        }
    }
}

@Composable private fun AccountRow(signedIn: Boolean, hasClientKey: Boolean, draft: String, onDraft: (String) -> Unit, message: String?, onSignIn: () -> Unit, onSignOut: () -> Unit, onSaveClient: () -> Unit) {
    Column(Modifier.testTag("mmf-account"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when {
            signedIn -> Row(verticalAlignment = Alignment.CenterVertically) { Text("Signed in - downloads enabled", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall); TextButton(onSignOut, modifier = Modifier.testTag("mmf-signout")) { Text("Sign out") } }
            hasClientKey -> Row(verticalAlignment = Alignment.CenterVertically) { Text("Downloading needs a MyMiniFactory sign-in.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall); OutlinedButton(onSignIn, modifier = Modifier.testTag("mmf-signin")) { Text("Sign in") } }
            else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(draft, onDraft, label = { Text("Client key (enables sign-in)") }, singleLine = true, modifier = Modifier.weight(1f).testTag("mmf-client-field"))
                OutlinedButton(onSaveClient, enabled = draft.isNotBlank(), modifier = Modifier.testTag("mmf-client-save")) { Text("Save") }
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("mmf-account-message")) }
    }
}

@Composable private fun LicenseChips(license: MmfLicense) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        val chips = buildList {
            if (license.terms[MmfLicenseTerm.REMIX] == true) add("Remix OK")
            if (license.terms[MmfLicenseTerm.COMMERCIAL_USE] == true) add("Commercial OK")
            if (license.creditRequired) add("Credit required")
            if (license.isPaid) add("Paid")
        }
        items(chips) { Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer) { Text(it, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)) } }
    }
}

@Composable private fun ResultCard(o: MmfObject, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick).semantics(mergeDescendants = true) {}.testTag("mmf-result-${o.id}")) {
        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RemoteImage(o.coverThumbnail, Modifier.size(84.dp), targetPx = 200)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(o.name, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                Text("by ${o.designer?.name?.ifBlank { null } ?: o.designer?.username ?: "unknown"}", style = MaterialTheme.typography.bodySmall)
                Text("♥ ${o.likes}   👁 ${o.views}", style = MaterialTheme.typography.labelSmall)
                LicenseChips(o.license)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun ModelDetail(
    summary: MmfObject, api: MmfApi, signedIn: Boolean, auth: MmfAuthManager?, dao: ProjectDao,
    onClose: () -> Unit, onSignIn: () -> Unit, onOpenProject: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var detail by remember(summary.id) { mutableStateOf(summary) }
    var files by remember(summary.id) { mutableStateOf(summary.files) }
    var picked by remember(summary.id) { mutableStateOf<Set<Long>?>(null) }
    var error by remember(summary.id) { mutableStateOf<String?>(null) }
    var status by remember(summary.id) { mutableStateOf<String?>(null) }
    var working by remember(summary.id) { mutableStateOf(false) }

    LaunchedEffect(summary.id) {
        try {
            val d = io { api.objectDetail(summary.id) }; detail = d
            files = d.files.ifEmpty { io { api.objectFiles(summary.id) }.items }
        } catch (e: MmfException) { error = e.message } catch (e: Exception) { error = "Could not load this model: ${e.message}" }
    }
    val printable = files.filter { it.isModel || it.isArchive }
    val chosen = picked ?: printable.map { it.id }.toSet()

    Surface(Modifier.fillMaxSize().testTag("mmf-detail"), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClose, modifier = Modifier.testTag("mmf-detail-close")) { Text("‹ Back") }
                Text(detail.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).testTag("mmf-detail-title"))
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(detail.images.mapNotNull { it.standardUrl ?: it.thumbnailUrl }.take(8)) { RemoteImage(it, Modifier.size(200.dp), targetPx = 500) } }
            Text(detail.attribution(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("mmf-attribution"))
            detail.url?.let { url -> TextButton({ runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) } }, modifier = Modifier.testTag("mmf-open-web")) { Text("Open on MyMiniFactory") } }
            Text("License", style = MaterialTheme.typography.titleSmall)
            Column(Modifier.semantics(mergeDescendants = true) {}.testTag("mmf-license")) { detail.license.statements().forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) } }
            if (detail.description.isNotBlank()) Text(detail.description.take(1500), style = MaterialTheme.typography.bodySmall)
            if (detail.printingDetails.isNotBlank()) { Text("Printing notes", style = MaterialTheme.typography.titleSmall); Text(detail.printingDetails.take(1000), style = MaterialTheme.typography.bodySmall) }
            Text("Files", style = MaterialTheme.typography.titleSmall)
            if (printable.isEmpty()) Text("No STL, 3MF, OBJ or ZIP files were listed for this model.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("mmf-no-files"))
            printable.forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("mmf-file-${f.id}")) {
                    Checkbox(f.id in chosen, { on -> picked = if (on) chosen + f.id else chosen - f.id })
                    Text("${f.filename}" + (f.sizeBytes?.let { "  ·  ${formatBytes(it)}" } ?: ""), style = MaterialTheme.typography.bodySmall)
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("mmf-detail-error")) }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("mmf-status")) }
            if (!signedIn) {
                OutlinedButton(onSignIn, enabled = auth != null, modifier = Modifier.fillMaxWidth().testTag("mmf-detail-signin")) { Text(if (auth != null) "Sign in to download" else "Add a client key on the Discover page to enable downloads") }
            }
            Button({
                scope.launch {
                    working = true; error = null
                    try {
                        val token = io { auth?.validAccessToken() } ?: throw MmfException.Unauthorized(needsSignIn = true)
                        val id = MmfImporter(api, context, dao).import(detail, printable.filter { it.id in chosen }, token) { status = it }
                        onOpenProject(id)
                    } catch (e: MmfException) { error = e.message } catch (e: IllegalStateException) { error = e.message } catch (e: Exception) { error = "Import failed: ${e.message}" }
                    working = false; status = null
                }
            }, enabled = signedIn && !working && chosen.isNotEmpty(), modifier = Modifier.fillMaxWidth().testTag("mmf-download")) { Text(if (working) "Working…" else "Download and start a project") }
            Text("Credit is saved with the project. Respect the license above when you print, share or sell.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal fun formatBytes(b: Long): String = when { b >= 1L shl 20 -> "%.1f MB".format(b / 1048576.0); b >= 1L shl 10 -> "%d KB".format(b / 1024); else -> "$b B" }
