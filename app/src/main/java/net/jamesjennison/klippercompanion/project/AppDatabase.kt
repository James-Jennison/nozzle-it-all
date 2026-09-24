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

// Phase 8 (WO-25): version 3 - project_objects gains toolSlotIndex, the real per-object tool/
// extruder assignment ToolSlots.kt's own data model needs (§11's ToolSlot/MaterialAssignment).
// Same real-migration discipline as MIGRATION_1_2 - existing projects must keep their objects,
// not lose them to a destructive fallback.
val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE project_objects ADD COLUMN toolSlotIndex INTEGER")
    }
}

// Phase 9b: version 4 - the plates table (multi-plate projects).
val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `plates` (`id` TEXT NOT NULL, `projectId` TEXT NOT NULL, `position` INTEGER NOT NULL, `name` TEXT NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`projectId`) REFERENCES `projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_plates_projectId` ON `plates` (`projectId`)")
    }
}

// Phase 9d: version 5 - per-object paint strokes and modifier/blocker volumes.
val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE project_objects ADD COLUMN paintJson TEXT")
        db.execSQL("ALTER TABLE project_objects ADD COLUMN volumesJson TEXT")
    }
}

// Phase 9e: version 6 - projects.calibration.
val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("ALTER TABLE projects ADD COLUMN calibration TEXT") }
}

// Phase 10: version 7 - projects.attribution.
val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("ALTER TABLE projects ADD COLUMN attribution TEXT") }
}

@Database(entities = [Project::class, ProjectObject::class, Plate::class], version = 7, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, AppDatabase::class.java, "nozzle_it_all.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7).build().also { instance = it }
        }
    }
}
