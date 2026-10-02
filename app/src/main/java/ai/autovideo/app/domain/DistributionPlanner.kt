package ai.autovideo.app.domain

import ai.autovideo.app.data.local.SceneEntity
import ai.autovideo.app.data.model.DistributionMode
import ai.autovideo.app.data.model.MediaKind
import kotlin.math.abs
import kotlin.math.roundToLong

/** Places an exact number of video/image slots evenly across the full narration. */
data class PlannedMediaSlot(
    val slotIndex: Int,
    val sceneIndex: Int,
    val mediaKind: MediaKind,
    val timelineStartMs: Long,
    val timelineEndMs: Long
)

object DistributionPlanner {
    fun plan(
        scenes: List<SceneEntity>,
        durationMs: Long,
        videoCount: Int,
        imageCount: Int,
        mode: DistributionMode
    ): List<PlannedMediaSlot> {
        val videos = videoCount.coerceAtLeast(0)
        val images = imageCount.coerceAtLeast(0)
        val total = videos + images
        if (total == 0 || scenes.isEmpty()) return emptyList()
        val safeDuration = durationMs.takeIf { it > 0 }
            ?: scenes.maxOfOrNull { it.endMs }?.coerceAtLeast(total.toLong())
            ?: total.toLong()
        val sortedScenes = scenes.sortedBy { it.startMs }
        val types = buildTypeOrder(videos, images, mode).toMutableList()

        fun sceneForSlot(slot: Int): SceneEntity {
            if (mode == DistributionMode.ONE_PER_SCENE && total <= sortedScenes.size) {
                val distinctPosition = (slot.toDouble() / total * sortedScenes.size).toInt()
                    .coerceIn(0, sortedScenes.lastIndex)
                return sortedScenes[distinctPosition]
            }
            val midpoint = (safeDuration.toDouble() * (slot + 0.5) / total).toLong()
            return sortedScenes.minByOrNull { scene ->
                val sceneMidpoint = scene.startMs + (scene.endMs - scene.startMs) / 2
                abs(sceneMidpoint - midpoint)
            } ?: sortedScenes.first()
        }

        // Preserve exact quotas while swapping a take toward the media kind requested by that micro-scene.
        if (mode == DistributionMode.AUTOMATIC || mode == DistributionMode.PROPORTIONAL || mode == DistributionMode.ONE_PER_SCENE) {
            for (slot in types.indices) {
                val preferred = preferredKind(sceneForSlot(slot))
                if (types[slot] == preferred) continue
                val swapWith = (slot + 1 until types.size).firstOrNull { later ->
                    types[later] == preferred && preferredKind(sceneForSlot(later)) != preferred
                }
                if (swapWith != null) {
                    types[slot] = preferred
                    types[swapWith] = if (preferred == MediaKind.VIDEO) MediaKind.IMAGE else MediaKind.VIDEO
                }
            }
        }

        return types.mapIndexed { slotIndex, mediaKind ->
            val start = (safeDuration.toDouble() * slotIndex / total).roundToLong().coerceIn(0, safeDuration)
            val end = (safeDuration.toDouble() * (slotIndex + 1) / total).roundToLong().coerceIn(start, safeDuration)
            val scene = sceneForSlot(slotIndex)
            PlannedMediaSlot(slotIndex, scene.sceneIndex, mediaKind, start, end)
        }
    }

    private fun preferredKind(scene: SceneEntity): MediaKind =
        if (scene.preferredMedia.equals("image", ignoreCase = true)) MediaKind.IMAGE else MediaKind.VIDEO

    private fun buildTypeOrder(videoCount: Int, imageCount: Int, mode: DistributionMode): List<MediaKind> {
        val total = videoCount + imageCount
        if (total == 0) return emptyList()
        var videos = 0
        var images = 0
        val result = ArrayList<MediaKind>(total)
        repeat(total) { position ->
            val next = when {
                videos >= videoCount -> MediaKind.IMAGE
                images >= imageCount -> MediaKind.VIDEO
                mode == DistributionMode.ALTERNATE -> if (position % 2 == 0) {
                    if (videos < videoCount) MediaKind.VIDEO else MediaKind.IMAGE
                } else if (images < imageCount) MediaKind.IMAGE else MediaKind.VIDEO
                mode == DistributionMode.PRIORITIZE_VIDEO -> {
                    val videoProgress = (videos + 0.5) / videoCount.coerceAtLeast(1)
                    val imageProgress = (images + 0.5) / imageCount.coerceAtLeast(1)
                    if (videoProgress <= imageProgress + 0.06) MediaKind.VIDEO else MediaKind.IMAGE
                }
                mode == DistributionMode.PRIORITIZE_IMAGE -> {
                    val videoProgress = (videos + 0.5) / videoCount.coerceAtLeast(1)
                    val imageProgress = (images + 0.5) / imageCount.coerceAtLeast(1)
                    if (imageProgress <= videoProgress + 0.06) MediaKind.IMAGE else MediaKind.VIDEO
                }
                else -> {
                    // Weighted fair scheduling keeps the requested ratios from grouping at either end.
                    val nextVideoPosition = (videos + 0.5) / videoCount.coerceAtLeast(1)
                    val nextImagePosition = (images + 0.5) / imageCount.coerceAtLeast(1)
                    if (nextVideoPosition <= nextImagePosition) MediaKind.VIDEO else MediaKind.IMAGE
                }
            }
            when (next) {
                MediaKind.VIDEO -> videos++
                MediaKind.IMAGE -> images++
            }
            result += next
        }
        return result
    }
}
