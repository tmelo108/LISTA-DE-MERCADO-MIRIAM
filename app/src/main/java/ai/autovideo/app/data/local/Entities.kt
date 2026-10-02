package ai.autovideo.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "projects", indices = [Index(value = ["createdAt"])])
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val folderPath: String,
    val narrationPath: String,
    val narrationName: String,
    val narrationSizeBytes: Long,
    val durationMs: Long,
    val settingsJson: String,
    val status: String = "QUEUED",
    val currentStage: String = "Aguardando processamento",
    val progress: Int = 0,
    val totalScenes: Int = 0,
    val requestedVideos: Int = 0,
    val requestedImages: Int = 0,
    val foundVideos: Int = 0,
    val foundImages: Int = 0,
    val missingMedia: Int = 0,
    val outputPath: String? = null,
    val previewPath: String? = null,
    val thumbnailPath: String? = null,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "scenes",
    primaryKeys = ["projectId", "sceneIndex"],
    indices = [Index(value = ["projectId"])]
)
data class SceneEntity(
    val projectId: String,
    val sceneIndex: Int,
    val startMs: Long,
    val endMs: Long,
    val durationMs: Long,
    val narration: String,
    val visualDescription: String,
    val searchQueriesJson: String,
    val preferredMedia: String
)

@Entity(
    tableName = "media_assets",
    indices = [
        Index(value = ["projectId", "sceneIndex"]),
        Index(value = ["mediaType"]),
        Index(value = ["sourceUrl"])
    ]
)
data class MediaAssetEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val sceneIndex: Int,
    val slotIndex: Int,
    val timelineStartMs: Long,
    val timelineEndMs: Long,
    val mediaType: String,
    val title: String,
    val description: String,
    val tags: String,
    val source: String,
    val sourceUrl: String?,
    val localPath: String,
    val thumbnailUrl: String?,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val score: Float,
    val author: String,
    val attributionUrl: String?,
    val isLocalLibraryItem: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "presets")
data class PresetEntity(
    @PrimaryKey val name: String,
    val settingsJson: String,
    val updatedAt: Long = System.currentTimeMillis()
)
