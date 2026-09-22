package net.jamesjennison.klippercompanion.project

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

// Phase 0 (Consumer Slicer Plan §16): minimal real CRUD, not a stub - proves the Project/
// ProjectObject schema actually round-trips through Room before any UI depends on it. Phase 1
// is what wires this DAO into a real ProjectViewModel/workspace.
@Dao
interface ProjectDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProject(project: Project)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertObjects(objects: List<ProjectObject>)

    @Update
    suspend fun updateProject(project: Project)

    @Delete
    suspend fun deleteProject(project: Project)

    @Query("SELECT * FROM projects ORDER BY modifiedAt DESC")
    fun observeProjects(): Flow<List<Project>>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun getProject(id: String): Project?

    @Query("SELECT * FROM project_objects WHERE projectId = :projectId")
    suspend fun getObjectsForProject(projectId: String): List<ProjectObject>

    // Loads a project with its objects in one round trip - the shape Phase 1's workspace will
    // actually want, even though nothing calls this yet.
    @Transaction
    suspend fun loadProjectWithObjects(id: String): Pair<Project, List<ProjectObject>>? {
        val project = getProject(id) ?: return null
        return project to getObjectsForProject(id)
    }
}
