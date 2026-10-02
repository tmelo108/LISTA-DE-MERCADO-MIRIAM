package ai.autovideo.app.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    fun observeProjects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id LIMIT 1")
    fun observeProject(id: String): Flow<ProjectEntity?>

    @Query("SELECT * FROM projects WHERE id = :id LIMIT 1")
    suspend fun getProject(id: String): ProjectEntity?

    @Upsert
    suspend fun upsertProject(project: ProjectEntity)

    @Query("UPDATE projects SET status = :status, currentStage = :stage, progress = :progress, errorMessage = :error, updatedAt = :updatedAt WHERE id = :projectId")
    suspend fun updateProgress(
        projectId: String,
        status: String,
        stage: String,
        progress: Int,
        error: String? = null,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("UPDATE projects SET totalScenes = :totalScenes, foundVideos = :videos, foundImages = :images, missingMedia = :missing, status = :status, currentStage = :stage, progress = :progress, errorMessage = :error, updatedAt = :updatedAt WHERE id = :projectId")
    suspend fun updateMediaSummary(
        projectId: String,
        totalScenes: Int,
        videos: Int,
        images: Int,
        missing: Int,
        status: String,
        stage: String,
        progress: Int,
        error: String? = null,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("UPDATE projects SET outputPath = :path, previewPath = :previewPath, status = :status, currentStage = :stage, progress = :progress, errorMessage = NULL, updatedAt = :updatedAt WHERE id = :projectId")
    suspend fun setOutput(
        projectId: String,
        path: String?,
        previewPath: String? = null,
        status: String,
        stage: String,
        progress: Int,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun deleteProjectRow(id: String)

    @Query("SELECT * FROM scenes WHERE projectId = :projectId ORDER BY sceneIndex")
    fun observeScenes(projectId: String): Flow<List<SceneEntity>>

    @Query("SELECT * FROM scenes WHERE projectId = :projectId ORDER BY sceneIndex")
    suspend fun getScenes(projectId: String): List<SceneEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertScenes(scenes: List<SceneEntity>)

    @Query("DELETE FROM scenes WHERE projectId = :projectId")
    suspend fun deleteScenes(projectId: String)

    @Query("SELECT * FROM media_assets WHERE projectId = :projectId ORDER BY timelineStartMs, slotIndex")
    fun observeProjectAssets(projectId: String): Flow<List<MediaAssetEntity>>

    @Query("SELECT * FROM media_assets WHERE projectId = :projectId ORDER BY timelineStartMs, slotIndex")
    suspend fun getProjectAssets(projectId: String): List<MediaAssetEntity>

    @Query("SELECT * FROM media_assets WHERE projectId = :projectId AND sceneIndex = :sceneIndex ORDER BY slotIndex")
    suspend fun getAssetsForScene(projectId: String, sceneIndex: Int): List<MediaAssetEntity>

    @Query("SELECT * FROM media_assets WHERE projectId = :projectId AND mediaType = :type ORDER BY score DESC")
    suspend fun getProjectAssetsByType(projectId: String, type: String): List<MediaAssetEntity>

    @Query("SELECT * FROM media_assets WHERE projectId = :libraryId ORDER BY createdAt DESC")
    fun observeLibrary(libraryId: String): Flow<List<MediaAssetEntity>>

    @Query("SELECT * FROM media_assets WHERE projectId = :libraryId")
    suspend fun getLibraryAssets(libraryId: String): List<MediaAssetEntity>

    @Upsert
    suspend fun upsertAsset(asset: MediaAssetEntity)

    @Upsert
    suspend fun upsertAssets(assets: List<MediaAssetEntity>)

    @Delete
    suspend fun deleteAsset(asset: MediaAssetEntity)

    @Query("DELETE FROM media_assets WHERE projectId = :projectId")
    suspend fun deleteProjectAssets(projectId: String)

    @Query("SELECT * FROM presets ORDER BY updatedAt DESC")
    fun observePresets(): Flow<List<PresetEntity>>

    @Upsert
    suspend fun upsertPreset(preset: PresetEntity)

    @Query("DELETE FROM presets WHERE name = :name")
    suspend fun deletePreset(name: String)
}
