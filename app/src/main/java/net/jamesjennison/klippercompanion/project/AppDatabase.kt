package net.jamesjennison.klippercompanion.project

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

// Phase 0 (Consumer Slicer Plan §16): version 1 - Project/ProjectObject only, empty/unused this
// phase. There's no prior schema to migrate from, so no Migration objects exist yet; Phase 1
// (once real data starts landing here) is what first has to write one, and should export the
// schema (room { schemaLocation }, app/build.gradle.kts) from that point on so migrations can be
// tested against a real prior version instead of guessed at.
//
// Phase 3 (WO-19): version 2 - project_objects gains materialDisplayName/materialTempNozzleC/
// materialTempBedC (materialId already existed as of version 1, planned ahead but unused). A real
// ALTER TABLE migration, not fallbackToDestructiveMigration() - by the time this shipped, real
// projects already existed in this app's own testing (and potentially the owner's), and this
// app's own stated principle is that drafts/project state must never silently vanish (§20).
val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE project_objects ADD COLUMN materialDisplayName TEXT")
        db.execSQL("ALTER TABLE project_objects ADD COLUMN materialTempNozzleC INTEGER")
        db.execSQL("ALTER TABLE project_objects ADD COLUMN materialTempBedC INTEGER")
    }
}

@Database(entities = [Project::class, ProjectObject::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, AppDatabase::class.java, "nozzle_it_all.db",
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
    }
}
