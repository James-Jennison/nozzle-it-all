package net.jamesjennison.klippercompanion

import org.junit.Assert.*
import org.junit.Test

class MyMiniFactoryParserTest {
    private val object1 = """{"id":123,"url":"https://www.myminifactory.com/object/3d-print-dragon-123","name":"Dragon\u0007 Bust","visibility":"2",
      "description":"A <b>dragon</b>","description_html":"<script>alert(1)</script>","printing_details":"0.2mm, no supports","views":1500,"likes":42,"complexity":2,
      "dimensions":"120x80x60","published_at":"2026-01-02T03:04:05.000Z","featured":true,"archive_download_url":"https://cdn.example.com/a.zip",
      "designer":{"username":"alice","name":"Alice A","profile_url":"https://www.myminifactory.com/users/alice","avatar_thumbnail_url":"https://cdn.example.com/av.png"},
      "images":[{"is_primary":false,"thumbnail":{"url":"https://cdn.example.com/t2.jpg"}},{"is_primary":true,"thumbnail":{"url":"https://cdn.example.com/t1.jpg"},"standard":{"url":"http://insecure.example.com/s.jpg"}}],
      "files":[{"id":9,"filename":"dragon.stl","size":"2048","download_url":"https://cdn.example.com/dragon.stl","viewer_url":"https://cdn.example.com/v.stl"},{"id":10,"filename":"../evil/x.STL","size":1}],
      "tags":["dragon","fantasy"],
      "licenses":[{"type":"mention","value":true},{"type":"remix","value":false},{"type":"commercial-use","value":false},{"type":"store","value":true},{"type":"bogus","value":true}]}"""

    @Test fun parsesASearchPageWithImagesFilesDesignerAndLicense() {
        val page = MmfParser.parseSearch("""{"total_count":812,"items":[$object1]}""")
        assertEquals(812, page.totalCount)
        val o = page.items.single()
        assertEquals(123L, o.id); assertEquals("Dragon Bust", o.name) // control character stripped
        assertEquals("https://cdn.example.com/t1.jpg", o.coverThumbnail) // the primary image wins
        assertEquals("alice", o.designer!!.username); assertEquals("https://cdn.example.com/av.png", o.designer!!.avatarUrl)
        assertEquals(listOf("dragon", "fantasy"), o.tags); assertEquals(1500, o.views); assertTrue(o.featured)
        assertNull("http (non-https) URLs are dropped", o.images.first { it.primary }.standardUrl)
        assertEquals("A <b>dragon</b>", o.description) // plain description only; the HTML field is never surfaced
    }

    @Test fun filesAreSanitisedAndClassified() {
        val o = MmfParser.parseObject(object1)
        assertEquals(2, o.files.size)
        val f = o.files[0]; assertEquals(2048L, f.sizeBytes); assertTrue(f.isModel); assertEquals("https://cdn.example.com/dragon.stl", f.downloadUrl)
        assertEquals("path separators cannot survive in a filename", ".._evil_x.STL", o.files[1].filename); assertTrue(o.files[1].isModel)
        assertFalse(MmfFile(1, "readme.txt", null, null, null, null).isModel); assertTrue(MmfFile(1, "all.ZIP", null, null, null, null).isArchive)
    }

    @Test fun licenseStatementsAreHonestAndNeverGuessed() {
        val l = MmfParser.parseObject(object1).license
        assertTrue(l.creditRequired); assertTrue(l.isPaid)
        val s = l.statements()
        assertTrue(s.any { it.startsWith("Credit the designer") }); assertTrue(s.contains("Remixing is not allowed.")); assertTrue(s.contains("No commercial use."))
        assertTrue(s.any { it.startsWith("Paid model") })
        assertFalse("terms the API did not report are not asserted", s.any { it.contains("Sharing is allowed") || it.contains("Do not redistribute") })
        assertTrue(MmfLicense(emptyMap()).statements().single().contains("no license details"))
    }

    @Test fun attributionNamesTheDesignerTheSourceAndTheLink() {
        assertEquals("\"Dragon Bust\" by Alice A on MyMiniFactory - https://www.myminifactory.com/object/3d-print-dragon-123", MmfParser.parseObject(object1).attribution())
        assertTrue(MmfParser.parseObject("""{"id":1,"name":"X"}""").attribution().contains("unknown designer"))
    }

    @Test fun malformedAndHostileInputIsRejectedOrDropped() {
        assertThrows(MmfParseException::class.java) { MmfParser.parseSearch("not json") }
        assertThrows(MmfParseException::class.java) { MmfParser.parseSearch("""{"total_count":1}""") }
        assertThrows(MmfParseException::class.java) { MmfParser.parseObject("""{"name":"no id"}""") }
        val page = MmfParser.parseSearch("""{"total_count":3,"items":[{"id":1},{"id":2,"name":"ok","url":"javascript:alert(1)","images":[{"thumbnail":{"url":"file:///etc/passwd"}}]},"junk",{"name":"no id"}]}""")
        assertEquals(1, page.items.size); assertNull(page.items[0].url); assertNull(page.items[0].coverThumbnail)
        assertNull(MmfParser.parseFile(org.json.JSONObject("""{"id":1,"filename":"","size":5}""")))
        val creds = MmfParser.parseObject("""{"id":1,"name":"n","url":"https://user:pw@www.myminifactory.com/o/1"}""")
        assertNull("credentials in a URL are rejected", creds.url)
    }

    @Test fun hugeListsAreCapped() {
        val items = (1..500).joinToString(",") { """{"id":$it,"name":"n$it"}""" }
        assertEquals(200, MmfParser.parseSearch("""{"total_count":9999,"items":[$items]}""").items.size)
    }

    // Structure captured from the live API (trimmed text): "files" is an object with items, and deleted models appear in results.
    @Test fun parsesARealLiveSearchResponse() {
        val json = javaClass.getResourceAsStream("/mmf/search_live_sample.json")!!.readBytes().decodeToString()
        val page = MmfParser.parseSearch(json)
        assertEquals("the deleted model is dropped, the approved one kept", 1, page.items.size)
        val o = page.items.single()
        assertTrue(o.files.isNotEmpty()); assertTrue(o.files.first().filename.isNotBlank()); assertTrue(o.files.first().isModel)
        assertNull("the API omits the download link without OAuth", o.files.first().downloadUrl)
        assertNotNull(o.coverThumbnail); assertTrue(o.coverThumbnail!!.startsWith("https://"))
        assertNotNull(o.designer); assertTrue(o.url!!.startsWith("https://www.myminifactory.com/"))
        assertTrue(o.license.terms.containsKey(MmfLicenseTerm.REMIX)); assertTrue(o.license.statements().isNotEmpty())
        assertEquals(3821, page.totalCount)
    }

    @Test fun filesAsAPlainArrayStillWork() {
        assertEquals(1, MmfParser.parseObject("""{"id":1,"name":"n","files":[{"id":2,"filename":"a.stl"}]}""").files.size)
        assertEquals(1, MmfParser.parseObject("""{"id":1,"name":"n","files":{"total_count":1,"items":[{"id":2,"filename":"a.stl"}]}}""").files.size)
        assertTrue("a non-approved model is not shown", MmfParser.parseSearch("""{"total_count":1,"items":[{"id":1,"name":"n","status_name":"pending"}]}""").items.isEmpty())
    }
}
