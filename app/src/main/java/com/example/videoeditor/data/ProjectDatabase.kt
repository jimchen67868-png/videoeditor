package com.example.videoeditor.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import com.google.gson.Gson

/**
 * Stores each project as a single JSON blob rather than a normalized
 * clip/overlay table structure. This scaffold only ever keeps one "current"
 * project (like a single ongoing draft, not a project library), so the
 * simplicity is worth it -- revisit if multi-project management is added
 * later, at which point normalizing clips into their own table would make
 * more sense for querying/updating individual clips efficiently.
 */
@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val json: String,
    val updatedAt: Long
)

@Dao
interface ProjectDao {
    @Upsert
    suspend fun upsert(entity: ProjectEntity)

    @Query("SELECT * FROM projects ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getMostRecent(): ProjectEntity?

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Database(entities = [ProjectEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "video_editor.db"
                ).build().also { instance = it }
            }
    }
}

/**
 * Wraps Room + Gson so callers work entirely in terms of [com.example.videoeditor.model.Project],
 * never touching JSON or entities directly.
 */
class ProjectRepository(context: Context) {
    private val dao = AppDatabase.get(context).projectDao()
    private val gson = Gson()

    suspend fun save(project: com.example.videoeditor.model.Project) {
        val json = gson.toJson(project.toDto())
        dao.upsert(ProjectEntity(project.id, project.name, json, System.currentTimeMillis()))
    }

    suspend fun loadMostRecent(): com.example.videoeditor.model.Project? {
        val entity = dao.getMostRecent() ?: return null
        return runCatching {
            gson.fromJson(entity.json, ProjectDto::class.java).toModel()
        }.getOrNull()
    }
}
