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
    val updatedAt: Long,
    // JSON arrays of ProjectDto, oldest-first. Undo/redo history used to live
    // only in EditorViewModel's in-memory ArrayDeques, so it reset to empty
    // every time the app was closed and reopened even though the project
    // itself reloaded fine. Persisting it here alongside the project fixes
    // that. Defaults to "[]" so existing rows survive the migration below.
    val undoStackJson: String = "[]",
    val redoStackJson: String = "[]"
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

@Database(entities = [ProjectEntity::class], version = 2, exportSchema = false)
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
                )
                    // This scaffold only ever keeps one current project (see
                    // the class doc below), so a destructive migration on
                    // schema bumps is an acceptable tradeoff for staying
                    // simple -- there's no real user data at stake yet, just
                    // the single in-progress draft.
                    .fallbackToDestructiveMigration()
                    .build().also { instance = it }
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
    private val historyListType = com.google.gson.reflect.TypeToken.getParameterized(List::class.java, ProjectDto::class.java).type

    suspend fun save(
        project: com.example.videoeditor.model.Project,
        undoHistory: List<com.example.videoeditor.model.Project> = emptyList(),
        redoHistory: List<com.example.videoeditor.model.Project> = emptyList()
    ) {
        val json = gson.toJson(project.toDto())
        val undoJson = gson.toJson(undoHistory.map { it.toDto() })
        val redoJson = gson.toJson(redoHistory.map { it.toDto() })
        dao.upsert(ProjectEntity(project.id, project.name, json, System.currentTimeMillis(), undoJson, redoJson))
    }

    data class LoadedProject(
        val project: com.example.videoeditor.model.Project,
        val undoHistory: List<com.example.videoeditor.model.Project>,
        val redoHistory: List<com.example.videoeditor.model.Project>
    )

    suspend fun loadMostRecent(): LoadedProject? {
        val entity = dao.getMostRecent() ?: return null
        return runCatching {
            val project = gson.fromJson(entity.json, ProjectDto::class.java).toModel()
            // Old rows (pre-migration, or any row saved before this existed)
            // won't have history JSON -- runCatching per-list so a corrupt/
            // missing history never blocks loading the project itself.
            val undoHistory = runCatching {
                gson.fromJson<List<ProjectDto>>(entity.undoStackJson, historyListType).map { it.toModel() }
            }.getOrDefault(emptyList())
            val redoHistory = runCatching {
                gson.fromJson<List<ProjectDto>>(entity.redoStackJson, historyListType).map { it.toModel() }
            }.getOrDefault(emptyList())
            LoadedProject(project, undoHistory, redoHistory)
        }.getOrNull()
    }
}
