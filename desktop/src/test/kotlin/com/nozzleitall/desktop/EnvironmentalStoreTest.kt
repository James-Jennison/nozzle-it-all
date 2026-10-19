package com.nozzleitall.desktop

import com.nozzleitall.desktop.environment.JdbcEnvironmentalStore
import com.nozzleitall.environment.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.sql.SQLException

class EnvironmentalStoreTest {
    @Test fun autocommitFullFailureEvictsInvalidStatementAndCanRetryAfterDelete() {
        val path = Files.createTempDirectory("nia-env-jdbc-autocommit").resolve("history.db").toFile()
        JdbcEnvironmentalStore(path).use { sql ->
            sql.execute("PRAGMA max_page_count=256")
            sql.execute("CREATE TABLE fixture(value TEXT)")
            var full = false
            repeat(300) {
                if (!full) try { sql.execute("INSERT INTO fixture(value) VALUES(?)", "x".repeat(8192)) }
                catch (e: SQLException) { assertEquals(13, e.errorCode); full = true }
            }
            assertTrue(full)
            sql.execute("DELETE FROM fixture")
            sql.execute("INSERT INTO fixture(value) VALUES(?)", "recovered")
            assertEquals("1", sql.query("SELECT COUNT(*) FROM fixture").single().single())
        }
    }
    @Test fun actualJdbcStoreRetainsOriginalFullErrorAndRecoversAfterRetention() {
        val path = Files.createTempDirectory("nia-env-jdbc-full").resolve("history.db").toFile()
        EnvironmentalHistory(SqlStoreFactory { JdbcEnvironmentalStore(path) }, EnvironmentalLimits(historyBytes = 1024 * 1024)).use { h ->
            var full = false
            for (i in 0 until 3000) {
                val o = Observation("full-$i", "inc", "fixture", "run", i.toLong(), "binding", 1, 1000, 1001, 1,
                    decoder = BleDecoders.VERSION, readings = listOf(Reading(Channel.TEMPERATURE, 23.0)))
                try { h.append(o, "inc") }
                catch (e: SQLException) { assertEquals(13, e.errorCode); full = true; break }
            }
            assertTrue(full)
            assertTrue(path.length() <= 1024 * 1024)
            h.retain(31 * 86400000L)
            assertEquals(0L, h.eventCount())
            val fresh = Observation("fresh", "inc", "fixture", "run", 9999, "binding", 1, 1000, 1001, 1,
                decoder = BleDecoders.VERSION, readings = listOf(Reading(Channel.TEMPERATURE, 23.0)))
            assertEquals("inserted", h.append(fresh, "inc"))
        }
    }
}
