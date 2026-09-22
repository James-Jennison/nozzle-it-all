package net.jamesjennison.klippercompanion.project

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// Phase 0 (Consumer Slicer Plan §16): version 1 - Project/ProjectObject only, empty/unused this
// phase. There's no prior schema to migrate from, so no Migration objects exist yet; Phase 1
// (once real data starts landing here) is what first has to write one, and should export the
// schema (room { schemaLocation }, app/build.gradle.kts) from that point on so migrations can be
// tested against a real prior version instead of guessed at.
@Database(entities = [Project::class, ProjectObject::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, AppDatabase::class.java, "nozzle_it_all.db",
            ).build().also { instance = it }
        }
    }
}
