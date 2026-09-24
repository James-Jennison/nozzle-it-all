package net.jamesjennison.klippercompanion

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.jamesjennison.klippercompanion.project.AppDatabase
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// Talks to the REAL MyMiniFactory API. Runs only when the build was given MMF_API_KEY (gradle property/environment);
// otherwise every test is skipped, so CI and other machines are unaffected. Never prints the key.
@RunWith(AndroidJUnit4::class)
class MmfLiveDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun needsKey() { assumeTrue("no MMF_API_KEY in this build", BuildConfig.MMF_API_KEY.isNotBlank()) }
    private fun client() = MyMiniFactoryClient(BuildConfig.MMF_API_KEY)

    @Test fun searchReturnsRealParsedModelsWithLicensesImagesAndDesigners() {
        val page = client().search(MmfSearch("vase", perPage = 20))
        assertTrue("real catalogue: ${page.totalCount}", page.totalCount > 100); assertTrue(page.items.isNotEmpty())
        page.items.forEach { o ->
            assertTrue(o.name.isNotBlank()); assertTrue(o.url!!.startsWith("https://www.myminifactory.com/"))
            assertTrue("every result has license terms", o.license.terms.isNotEmpty())
            assertNotNull(o.designer)
        }
        assertTrue("most results have a cover image", page.items.count { it.coverThumbnail != null } >= page.items.size / 2)
        assertTrue("files come with each result", page.items.any { it.files.any { f -> f.isModel } })
    }

    @Test fun sortFiltersAndPagingChangeTheRealResults() {
        val popular = client().search(MmfSearch("dragon", perPage = 10, sort = MmfSort.POPULARITY)).items.map { it.id }
        val newest = client().search(MmfSearch("dragon", perPage = 10, sort = MmfSort.DATE)).items.map { it.id }
        assertNotEquals("sorting must matter", popular, newest)
        val page2 = client().search(MmfSearch("dragon", page = 2, perPage = 10, sort = MmfSort.POPULARITY)).items.map { it.id }
        assertTrue("page 2 continues where page 1 stopped", page2.intersect(popular.toSet()).size < popular.size)
        val remixable = client().search(MmfSearch("dragon", perPage = 20, remixAllowed = true)).items
        assertTrue("the remix filter returns only remixable models", remixable.isNotEmpty() && remixable.all { it.license.terms[MmfLicenseTerm.REMIX] == true })
    }

    @Test fun theFreePaidAndFdmFiltersNarrowTheRealCatalogue() {
        val all = client().search(MmfSearch("vase", perPage = 20)).totalCount
        val free = client().search(MmfSearch("vase", perPage = 20, price = MmfPrice.FREE))
        val paid = client().search(MmfSearch("vase", perPage = 20, price = MmfPrice.PAID))
        assertTrue("free and paid both narrow the catalogue", free.totalCount < all && paid.totalCount < all)
        assertTrue(free.items.isNotEmpty() && free.items.all { it.license.terms[MmfLicenseTerm.STORE] == false })
        assertTrue(paid.items.isNotEmpty() && paid.items.all { it.license.terms[MmfLicenseTerm.STORE] == true })
        val fdm = client().search(MmfSearch("vase", perPage = 20, fdmOnly = true))
        assertTrue("FDM-tagged models are a subset", fdm.items.isNotEmpty() && fdm.totalCount < all)
    }

    @Test fun detailAndFilesEndpointsAgreeWithSearch() {
        val hit = client().search(MmfSearch("vase", perPage = 5)).items.first { it.files.isNotEmpty() }
        val detail = client().objectDetail(hit.id)
        assertEquals(hit.name, detail.name)
        val files = client().objectFiles(hit.id)
        assertTrue(files.items.isNotEmpty()); assertEquals(detail.files.map { it.id }.toSet(), files.items.map { it.id }.toSet())
        assertTrue(detail.attribution().contains(hit.url!!))
    }

    @Test fun badKeysAndMissingModelsAreReportedInPlainWords() {
        assertThrows(MmfException.Unauthorized::class.java) { MyMiniFactoryClient("notarealkey0000").search(MmfSearch("a")) }
        assertThrows(MmfException.NotFound::class.java) { client().objectDetail(999_999_999) }
    }

    @Test fun downloadingWithoutSignInIsRefusedBeforeAnyNetworkTraffic() {
        val f = client().search(MmfSearch("vase", perPage = 5)).items.flatMap { it.files }.first { it.isModel }
        assertThrows(MmfException.Unauthorized::class.java) { client().download(f, "", java.io.File(ctx.cacheDir, "nope.stl")) }
    }

    @Test fun theDiscoverScreenBrowsesRealModelsAndShowsRealLicenseTerms() {
        val prefs = ctx.getSharedPreferences("mmf-live-${System.nanoTime()}", 0)
        val settings = MmfSettings(prefs, buildApiKey = BuildConfig.MMF_API_KEY, buildClientKey = "")
        compose.setContent { CompanionTheme { DiscoverScreen(settings, AppDatabase.get(ctx).projectDao(), null, {}, {}, {}) } }
        compose.waitUntil(30000) { compose.onAllNodes(hasTestTagStartingWith("mmf-result-")).fetchSemanticsNodes().isNotEmpty() || compose.onAllNodesWithTag("mmf-error").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithTag("mmf-error").assertCountEquals(0)
        compose.onAllNodes(hasTestTagStartingWith("mmf-result-")).onFirst().performClick()
        compose.waitUntil(30000) { compose.onAllNodesWithTag("mmf-detail-title").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("mmf-attribution").assertTextContains("on MyMiniFactory - https://www.myminifactory.com/", substring = true)
        compose.onNodeWithTag("mmf-license").assertExists()
        compose.onNodeWithTag("mmf-download").assertIsNotEnabled() // signed out
    }

    private fun hasTestTagStartingWith(prefix: String) = SemanticsMatcher("test tag starts with $prefix") { n ->
        n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }

    // Uses the phone's own MyMiniFactory session when there is one (read only); skipped otherwise. Prints no token.
    @Test fun aSignedInSessionMakesTheApiReturnDownloadLinks() {
        val session = MmfSettings(ctx).load()
        assumeTrue("not signed in to MyMiniFactory on this device", session != null && session.validAt(System.currentTimeMillis(), 0))
        val token = session!!.accessToken
        val without = client().objectFiles(26442).items.first().downloadUrl
        val withToken = client().objectFiles(26442, token).items.first().downloadUrl
        assertNull("the API key alone gets no link", without)
        assertNotNull("a signed-in token must yield a download link", withToken)
        assertTrue(withToken!!.startsWith("https://"))
        android.util.Log.i("MmfLive", "detail-embedded link with token: ${client().objectDetail(26442, token).files.firstOrNull()?.downloadUrl != null}")
    }

    @Test fun aSignedInSessionReallyDownloadsAFreeModelFile() {
        val session = MmfSettings(ctx).load()
        assumeTrue("not signed in to MyMiniFactory on this device", session != null && session.validAt(System.currentTimeMillis(), 0))
        val file = client().objectFiles(26442, session!!.accessToken).items.first()
        val target = java.io.File(ctx.cacheDir, "mmf-live-download.stl")
        try {
            val bytes = client().download(file, session.accessToken, target) { _, _ -> }
            assertTrue("a real STL arrived: $bytes bytes", bytes > 100_000 && target.length() == bytes)
        } finally { target.delete() }
    }

    @Test fun aCategoryFilterNarrowsTheRealCatalogue() {
        val all = client().search(MmfSearch(perPage = 5, fdmOnly = true)).totalCount
        val toys = client().search(MmfSearch(perPage = 5, fdmOnly = true, category = MmfCategories.tree.first { it.name == "Toys" }.id))
        assertTrue("Toys is a real, smaller slice: ${toys.totalCount} of $all", toys.items.isNotEmpty() && toys.totalCount in 1 until all)
    }

    @Test fun everyPriceFilterReturnsAPageThatFitsTheResponseLimit() {
        for (price in MmfPrice.entries) {
            val page = client().search(MmfSearch("", 1, 15, MmfSort.POPULARITY, price = price, fdmOnly = true))
            assertTrue("$price returned ${page.items.size} items", page.items.isNotEmpty())
        }
    }
}
