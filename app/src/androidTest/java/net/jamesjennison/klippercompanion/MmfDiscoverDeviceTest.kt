package net.jamesjennison.klippercompanion

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.jamesjennison.klippercompanion.project.AppDatabase
import net.jamesjennison.klippercompanion.project.ProjectViewModel
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class MmfDiscoverDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val createdProjects = mutableListOf<String>()
    private fun freshSettings(key: String = "", client: String = ""): MmfSettings {
        val prefs = ctx.getSharedPreferences("mmf-test-${System.nanoTime()}", 0).also { it.edit().clear().commit() }
        return MmfSettings(prefs, buildApiKey = key, buildClientKey = client)
    }
    @After fun cleanup() = runBlocking {
        val vm = ProjectViewModel(ctx, AppDatabase.get(ctx).projectDao()); createdProjects.forEach { if (vm.loadProject(it)) vm.deleteProject() }
    }

    private fun cubeBytes() = InstrumentationRegistry.getInstrumentation().context.assets.open("cube.stl").use { it.readBytes() }
    private fun zipOf(vararg e: Pair<String, ByteArray>) = java.io.ByteArrayOutputStream().also { b -> ZipOutputStream(b).use { z -> e.forEach { (n, d) -> z.putNextEntry(ZipEntry(n)); z.write(d); z.closeEntry() } } }.toByteArray()

    private fun obj(id: Long, name: String, licenses: Map<MmfLicenseTerm, Boolean> = mapOf(MmfLicenseTerm.MENTION to true, MmfLicenseTerm.REMIX to true), files: List<MmfFile> = emptyList()) =
        MmfObject(id, name, "https://www.myminifactory.com/object/$id", "Plain description of $name", "0.2mm", MmfDesigner("alice", "Alice A", null, null), emptyList(), files, listOf("tag"),
            MmfLicense(licenses), likes = 10 + id.toInt(), views = 100, dimensions = "", complexity = 1, publishedAt = null, featured = false, archiveDownloadUrl = null)

    private class FakeApi(val objects: List<MmfObject>, val detail: (Long) -> MmfObject = { id -> objects.first { it.id == id } }, val bytesFor: (MmfFile) -> ByteArray = { ByteArray(0) }) : MmfApi {
        val requests = mutableListOf<MmfSearch>(); var failWith: MmfException? = null; var downloads = 0
        override fun search(request: MmfSearch): MmfPage<MmfObject> { failWith?.let { throw it }; requests += request; return MmfPage(objects.size + 5, objects) }
        override fun objectDetail(id: Long) = detail(id)
        override fun objectFiles(id: Long) = MmfPage(0, emptyList<MmfFile>())
        override fun download(file: MmfFile, accessToken: String, target: File, onProgress: (Long, Long?) -> Unit): Long {
            downloads++; assertEquals("TOKEN-ABC", accessToken); target.writeBytes(bytesFor(file)); return target.length()
        }
    }
    private fun signedInAuth(settings: MmfSettings) = { _: MmfSettings -> MmfAuthManager(InMemoryMmfTokenStore().also { it.save(MmfSession("TOKEN-ABC", System.currentTimeMillis() + 3_600_000)) }, null) }
    private fun show(settings: MmfSettings, api: MmfApi?, auth: (MmfSettings) -> MmfAuthManager? = { null }, redirect: String? = null, opened: MutableList<String> = mutableListOf()) =
        compose.setContent { CompanionTheme { DiscoverScreen(settings, AppDatabase.get(ctx).projectDao(), redirect, {}, {}, { opened += it }, { api ?: error("no api") }, auth) } }

    @Test fun withoutAKeyTheSetupCardExplainsAndRejectsNonsenseKeys() {
        show(freshSettings(), null)
        compose.onNodeWithTag("mmf-setup").assertExists()
        compose.onAllNodesWithTag("mmf-search").assertCountEquals(0)
        compose.onNodeWithTag("mmf-key-save").assertIsNotEnabled()
        compose.onNodeWithTag("mmf-key-field").performTextInput("bad key!")
        compose.onNodeWithTag("mmf-key-save").performClick()
        compose.onNodeWithTag("mmf-key-error").assertTextContains("does not look like", substring = true)
    }

    @Test fun savingAValidKeyShowsSearchAndResultsWithHonestLicenseChips() {
        val api = FakeApi(listOf(obj(1, "Dragon", mapOf(MmfLicenseTerm.MENTION to true, MmfLicenseTerm.REMIX to true, MmfLicenseTerm.COMMERCIAL_USE to false)), obj(2, "Vase", mapOf(MmfLicenseTerm.STORE to true))))
        show(freshSettings(), api)
        compose.onNodeWithTag("mmf-key-field").performTextInput("abcdefgh1234"); compose.onNodeWithTag("mmf-key-save").performClick()
        compose.waitUntil(8000) { compose.onAllNodesWithTag("mmf-result-1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("mmf-result-1").assertTextContains("Dragon", substring = true).assertTextContains("Remix OK", substring = true)
        compose.onNodeWithTag("mmf-result-1").assert(hasText("Commercial OK", substring = true).not())
        compose.onNodeWithTag("mmf-result-2").assertTextContains("Paid", substring = true)
        compose.onNodeWithTag("mmf-result-1").assertTextContains("by Alice A", substring = true)
    }

    @Test fun sortAndFilterChipsIssueNewSearchesWithThoseParameters() {
        val api = FakeApi(listOf(obj(1, "A")))
        show(freshSettings(key = "buildkey1234"), api)
        compose.waitUntil(8000) { api.requests.isNotEmpty() }
        compose.onNodeWithTag("mmf-search").performTextInput("dragon head")
        compose.onNodeWithTag("mmf-sort-date").performClick(); compose.onNodeWithTag("mmf-filter-remix").performClick(); compose.onNodeWithTag("mmf-filter-support").performClick()
        compose.waitUntil(8000) { api.requests.last().supportFree }
        val r = api.requests.last()
        assertEquals("dragon head", r.query); assertEquals(MmfSort.DATE, r.sort); assertTrue(r.remixAllowed); assertFalse(r.commercialUse); assertEquals(1, r.page)
    }

    @Test fun loadMoreRequestsTheNextPageAndAppends() {
        val api = FakeApi(listOf(obj(1, "A")))
        show(freshSettings(key = "buildkey1234"), api)
        compose.waitUntil(8000) { compose.onAllNodesWithTag("mmf-more").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("mmf-more").performScrollTo().performClick()
        compose.waitUntil(8000) { api.requests.any { it.page == 2 } }
    }

    @Test fun apiFailuresAreExplainedInPlainWords() {
        val api = FakeApi(emptyList()).also { it.failWith = MmfException.RateLimited(9) }
        show(freshSettings(key = "buildkey1234"), api)
        compose.waitUntil(8000) { compose.onAllNodesWithTag("mmf-error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("mmf-error").assertTextContains("rate-limiting", substring = true).assertTextContains("9s", substring = true)
    }

    @Test fun theDetailShowsCreditLicenseAndFilesAndDownloadsNeedSignIn() {
        val f = MmfFile(11, "dragon.stl", 2048, "https://cdn.example.com/dragon.stl", null, null)
        val api = FakeApi(listOf(obj(1, "Dragon", mapOf(MmfLicenseTerm.MENTION to true, MmfLicenseTerm.COMMERCIAL_USE to false, MmfLicenseTerm.EXCLUSIVITY to true), files = listOf(f))))
        show(freshSettings(key = "buildkey1234", client = "clientkey1234"), api, auth = { s -> MmfAuthManager(InMemoryMmfTokenStore(), null) })
        compose.waitUntil(8000) { compose.onAllNodesWithTag("mmf-result-1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("mmf-result-1").performClick()
        compose.waitUntil(8000) { compose.onAllNodesWithTag("mmf-detail").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("mmf-attribution").assertTextContains("\"Dragon\" by Alice A on MyMiniFactory - https://www.myminifactory.com/object/1")
        compose.onNodeWithTag("mmf-license").assertTextContains("Credit the designer", substring = true).assertTextContains("No commercial use.", substring = true).assertTextContains("exclusively", substring = true)
        compose.onNodeWithTag("mmf-file-11").assertExists()
        compose.onNodeWithText("dragon.stl", substring = true).assertTextContains("2 KB", substring = true)
        compose.onNodeWithTag("mmf-download").assertIsNotEnabled()
        compose.onNodeWithTag("mmf-detail-signin").assertIsEnabled()
        compose.onNodeWithTag("mmf-detail-close").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("mmf-detail").fetchSemanticsNodes().isEmpty() }
    }

    @Test fun signedInDownloadUnpacksAnArchiveAndCreatesAProjectWithTheCreditLine() {
        val cube = cubeBytes()
        val stl = MmfFile(11, "single.stl", null, "https://cdn.example.com/single.stl", null, null)
        val zip = MmfFile(12, "pack.zip", null, "https://cdn.example.com/pack.zip", null, null)
        val readme = MmfFile(13, "readme.txt", null, "https://cdn.example.com/readme.txt", null, null)
        val bytes = { f: MmfFile -> when (f.id) { 11L -> cube; 12L -> zipOf("models/a.stl" to cube, "models/b.STL" to cube, "notes.txt" to byteArrayOf(1)); else -> byteArrayOf(1) } }
        val api = FakeApi(listOf(obj(7, "Castle Set", files = listOf(stl, zip, readme))), bytesFor = bytes)
        val opened = mutableListOf<String>()
        val settings = freshSettings(key = "buildkey1234", client = "clientkey1234")
        show(settings, api, auth = signedInAuth(settings), opened = opened)
        compose.waitUntil(8000) { compose.onAllNodesWithTag("mmf-result-7").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("mmf-result-7").performClick()
        compose.waitUntil(8000) { compose.onAllNodesWithTag("mmf-file-12").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithTag("mmf-file-13").assertCountEquals(0) // a readme is not a printable file
        compose.waitUntil(5000) { runCatching { compose.onNodeWithTag("mmf-download").assertIsEnabled() }.isSuccess }
        compose.onNodeWithTag("mmf-download").performScrollTo().performClick()
        compose.waitUntil(20000) { opened.isNotEmpty() }
        val id = opened.single(); createdProjects += id
        assertEquals(2, api.downloads)
        runBlocking {
            val vm = ProjectViewModel(ctx, AppDatabase.get(ctx).projectDao()); assertTrue(vm.loadProject(id))
            assertEquals("Castle Set", vm.project.value!!.name)
            assertEquals("\"Castle Set\" by Alice A on MyMiniFactory - https://www.myminifactory.com/object/7", vm.project.value!!.attribution)
            assertEquals("the single STL plus the two models unpacked from the zip", 3, vm.allObjects.value.size)
            vm.allObjects.value.forEach { assertTrue(File(android.net.Uri.parse(it.sourceFileUri).path!!).length() > 84) }
        }
        assertTrue("the working directory is cleaned up", ctx.cacheDir.listFiles().orEmpty().none { it.name.startsWith("mmf-") })
    }

    @Test fun aSignInRedirectWithTheWrongStateIsRejectedAndTheAccountStaysSignedOut() {
        val settings = freshSettings(key = "buildkey1234", client = "clientkey1234"); settings.beginSignIn()
        show(settings, FakeApi(emptyList()), auth = { MmfAuthManager(InMemoryMmfTokenStore(), null) }, redirect = "nozzleitall://mmf-auth#access_token=tok-abcdefgh&state=FORGED")
        compose.waitUntil(8000) { compose.onAllNodesWithTag("mmf-account-message").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("mmf-account-message").assertTextContains("did not match", substring = true)
        compose.onNodeWithTag("mmf-signin").assertExists()
        assertNull(settings.load()); assertNull("the pending state is consumed even on failure", settings.pendingState())
    }

    @Test fun settingsKeepUserKeysAheadOfBuildKeysAndForgetThemOnRequest() {
        val s = freshSettings(key = "buildkey1234")
        assertEquals("buildkey1234", s.apiKey()); s.saveApiKey("userkey56789"); assertEquals("userkey56789", s.apiKey()); assertTrue(s.hasUserApiKey())
        s.save(MmfSession("t-12345678", 99)); assertEquals("t-12345678", s.load()!!.accessToken)
        s.clearCredentials(); assertEquals("buildkey1234", s.apiKey()); assertNull(s.load())
        assertEquals(s.deviceId(), s.deviceId())
        assertFalse(MmfSettings.isPlausibleKey("short")); assertFalse(MmfSettings.isPlausibleKey("has space in it 123"))
    }

    @Test fun theEditorShowsTheCreditLineOfADownloadedModel() {
        val projectId = runBlocking {
            val vm = ProjectViewModel(ctx, AppDatabase.get(ctx).projectDao()); val p = vm.newProject("Credited"); createdProjects += p.id
            vm.setAttribution("\"Castle\" by Alice A on MyMiniFactory - https://www.myminifactory.com/object/7"); p.id
        }
        val address = "http://k-credit.local/"
        val state = ScreenState(address = address, connected = true, profiles = listOf(PrinterProfile(address, "K", kind = PrinterKind.GENERIC_KLIPPER, slicingModel = SlicingPrinterModel.GENERIC_KLIPPER)))
        compose.setContent { CompanionTheme { ProjectEditorScreen(projectId, null, state, { _, _ -> }, {}) } }
        compose.waitUntil(10000) { compose.onAllNodesWithTag("project-attribution").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("project-attribution").assertTextContains("by Alice A on MyMiniFactory", substring = true)
    }

    @Test fun freeAndPaidFiltersAskTheApiAndFdmOnlyIsExplained() {
        val api = FakeApi(listOf(obj(1, "A"), obj(2, "B")))
        show(freshSettings(key = "buildkey1234"), api)
        compose.waitUntil(8000) { api.requests.isNotEmpty() }
        compose.onNodeWithTag("mmf-filter-free").performClick(); compose.waitUntil(8000) { api.requests.last().price == MmfPrice.FREE }
        compose.onNodeWithTag("mmf-filter-paid").performClick(); compose.waitUntil(8000) { api.requests.last().price == MmfPrice.PAID }
        compose.onNodeWithTag("mmf-filter-all").performClick(); compose.waitUntil(8000) { api.requests.last().price == MmfPrice.ANY }
        compose.onAllNodesWithTag("mmf-filter-credit").assertCountEquals(0) // every model asks for credit, so it is not a filter
        compose.onAllNodesWithTag("mmf-fdm-note").assertCountEquals(0)
        compose.onNodeWithTag("mmf-filter-fdm").performClick()
        compose.waitUntil(8000) { api.requests.last().fdmOnly }
        compose.onNodeWithTag("mmf-fdm-note").assertTextContains("tagged FDM", substring = true)
        val n = api.requests.size; compose.onNodeWithTag("mmf-filter-fdm").performClick()
        compose.waitUntil(8000) { api.requests.size > n && !api.requests.last().fdmOnly }
        compose.onAllNodesWithTag("mmf-fdm-note").assertCountEquals(0)
    }

    // Regression: consuming the redirect used to cancel the in-flight sign-in (the effect's key changed), leaving the token
    // saved but the screen still saying "Sign in".
    @Test fun aSlowSignInFinishesAndTheScreenSwitchesToSignedIn() {
        val settings = freshSettings(key = "buildkey1234", client = "clientkey1234"); val state = settings.beginSignIn()
        val slow = object : MmfAuthManager(settings, null) {
            override fun completeSignIn(implicitToken: String, expiresInSeconds: Int, device: MmfDeviceInfo) { Thread.sleep(1500); settings.save(MmfSession(implicitToken, System.currentTimeMillis() + 3_600_000, false)) }
            override fun isSignedIn() = settings.load() != null
        }
        var redirect: String? = "nozzleitall://mmf-auth#access_token=tok-abcdefgh&expires_in=604800&state=$state"
        compose.setContent { CompanionTheme { DiscoverScreen(settings, AppDatabase.get(ctx).projectDao(), redirect, { redirect = null }, {}, {}, { FakeApi(emptyList()) }, { slow }) } }
        compose.waitUntil(15000) { compose.onAllNodesWithTag("mmf-signout").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("mmf-account-message").assertTextContains("Signed in", substring = true)
        compose.onAllNodesWithTag("mmf-signin").assertCountEquals(0)
        assertEquals("tok-abcdefgh", settings.load()!!.accessToken); assertNull(settings.pendingState())
    }

    // Persistence through the REAL encrypted store (a separate file, so the owner's own session is never touched):
    // a new MmfSettings over the same file is what an app restart sees.
    @Test fun aSignInSurvivesTheAppRestartingThroughTheEncryptedStore() {
        val file = "mmf-persistence-test-${System.nanoTime()}"
        try {
            val first = MmfSettings(CredentialStore.open(ctx, file), "", "")
            first.save(MmfSession("persisted-token-1", System.currentTimeMillis() + 3_600_000, refreshable = false)); first.saveSignInMessage("Signed in to MyMiniFactory.")
            val restarted = MmfSettings(CredentialStore.open(ctx, file), "", "")
            val s = restarted.load()!!
            assertEquals("persisted-token-1", s.accessToken); assertFalse(s.refreshable); assertTrue(s.expiresAtMs > System.currentTimeMillis())
            assertEquals("Signed in to MyMiniFactory.", restarted.lastSignInMessage())
            assertEquals(first.deviceId(), restarted.deviceId())
        } finally { ctx.deleteSharedPreferences(file) }
    }

    @Test fun anExpiredStoredSessionIsClearedAndExplainedNotShownAsSignedIn() {
        val settings = freshSettings(key = "buildkey1234", client = "clientkey1234")
        settings.save(MmfSession("old-token-1234", System.currentTimeMillis() - 1000, refreshable = false))
        show(settings, FakeApi(emptyList()), auth = { MmfAuthManager(settings, null) })
        compose.waitUntil(8000) { compose.onAllNodesWithTag("mmf-account-message").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("mmf-account-message").assertTextContains("expired", substring = true)
        compose.onNodeWithTag("mmf-signin").assertExists(); assertNull(settings.load())
    }

    @Test fun theSignedInRowShowsWhenTheSessionEnds() {
        val settings = freshSettings(key = "buildkey1234", client = "clientkey1234")
        settings.save(MmfSession("live-token-1234", System.currentTimeMillis() + 3 * 86_400_000L, refreshable = false))
        show(settings, FakeApi(emptyList()), auth = { MmfAuthManager(settings, null) })
        compose.waitUntil(8000) { compose.onAllNodesWithTag("mmf-signout").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Signed in - downloads enabled until", substring = true).assertExists()
    }

    @Test fun fdmOnlyAlsoHidesModelsThatMentionResin() {
        val fdm = obj(1, "Plain vase"); val resin = obj(2, "Bust | PRESUPPORTED | Free")
        val api = FakeApi(listOf(fdm, resin))
        show(freshSettings(key = "buildkey1234"), api)
        compose.waitUntil(8000) { compose.onAllNodesWithTag("mmf-result-1").fetchSemanticsNodes().isNotEmpty() }
        val has = { id: Long -> runCatching { compose.onNodeWithTag("mmf-results").performScrollToNode(hasTestTag("mmf-result-$id")) }.isSuccess }
        assertTrue(has(1)); assertTrue(has(2))
        val n = api.requests.size; compose.onNodeWithTag("mmf-filter-fdm").performClick()
        compose.waitUntil(8000) { api.requests.size > n && compose.onAllNodesWithTag("mmf-result-1").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(has(1)); assertFalse("the presupported (resin) model is hidden under FDM only", has(2))
    }
}
