package net.jamesjennison.klippercompanion

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import net.jamesjennison.klippercompanion.project.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Actual generated Room validation on a synthetic shipped-v7 fixture, never normal app data. */
class NormalUpgradeMigrationDeviceTest {
    @Test fun shippedSevenToNinePreservesEveryFixtureColumn() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".environment"))
        val file = File(context.cacheDir, "normal-upgrade-${java.util.UUID.randomUUID()}.db")
        val schema = JSONObject(instrumentation.context.assets.open("normal-upgrade-v7.json").bufferedReader().use { it.readText() })
            .getJSONObject("database")
        assertEquals(7, schema.getInt("version"))
        val entities = schema.getJSONArray("entities")
        val expected = linkedMapOf<String, Map<String, String>>()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            // Create all parent/child tables before loading the fixture.
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                entity.optJSONArray("indices")?.let { indices ->
                    for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                val fields = entity.getJSONArray("fields")
                val values = ContentValues()
                val original = linkedMapOf<String, String>()
                for (j in 0 until fields.length()) {
                    val field = fields.getJSONObject(j)
                    val column = field.getString("columnName")
                    val value = when (field.getString("affinity")) {
                        "REAL" -> "123.25"
                        "INTEGER" -> "123"
                        else -> if (column == "projectId" || table == "projects" && column == "id") "fixture-project" else "fixture-$table-$column"
                    }
                    when (field.getString("affinity")) {
                        "REAL" -> values.put(column, value.toDouble())
                        "INTEGER" -> values.put(column, value.toLong())
                        else -> values.put(column, value)
                    }
                    original[column] = value
                }
                assertTrue(db.insertOrThrow(table, null, values) != -1L)
                expected[table] = original
            }
            db.version = 7
        }
        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, file.absolutePath)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
            .build()
        try {
            // Opening invokes the actual generated Room schema validation and identity setup.
            val db = migrated.openHelper.writableDatabase
            db.query("PRAGMA user_version").use { assertTrue(it.moveToFirst()); assertEquals(9, it.getInt(0)) }
            expected.forEach { (table, columns) ->
                db.query("SELECT * FROM `$table`").use { cursor ->
                    assertEquals(1, cursor.count); assertTrue(cursor.moveToFirst())
                    columns.forEach { (column, value) -> assertEquals("$table.$column", value, cursor.getString(cursor.getColumnIndexOrThrow(column))) }
                }
            }
            db.query("SELECT mixedFilamentDefinitions,colorMixJson,processPreset FROM projects").use {
                assertTrue(it.moveToFirst()); repeat(3) { column -> assertTrue(it.isNull(column)) }
            }
            db.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
            db.query("PRAGMA integrity_check").use { assertTrue(it.moveToFirst()); assertEquals("ok", it.getString(0)) }
            println("NORMAL_UPGRADE actual_room_schema_validation=true from=7 to=9 preserved_tables=3 owner_data=false")
        } finally {
            migrated.close()
            // Only the uniquely named test-owned cache database is removed.
            assertTrue(context.deleteDatabase(file.absolutePath))
        }
    }
}
