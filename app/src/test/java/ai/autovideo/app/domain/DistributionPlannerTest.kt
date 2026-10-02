package ai.autovideo.app.domain

import ai.autovideo.app.data.local.SceneEntity
import ai.autovideo.app.data.model.DistributionMode
import ai.autovideo.app.data.model.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DistributionPlannerTest {
    private val scenes = (0 until 20).map { index ->
        SceneEntity("p", index, index * 2_000L, (index + 1) * 2_000L, 2_000, "fala", "visual", "[]", if (index % 2 == 0) "video" else "image")
    }

    @Test fun `keeps the exact requested counts and spreads slots through timeline`() {
        val slots = DistributionPlanner.plan(scenes, 40_000, 20, 10, DistributionMode.AUTOMATIC)
        assertEquals(30, slots.size)
        assertEquals(20, slots.count { it.mediaKind == MediaKind.VIDEO })
        assertEquals(10, slots.count { it.mediaKind == MediaKind.IMAGE })
        assertEquals(0L, slots.first().timelineStartMs)
        assertEquals(40_000L, slots.last().timelineEndMs)
        assertTrue(slots.zipWithNext().all { (a, b) -> a.timelineStartMs <= b.timelineStartMs })
        assertTrue(slots.map { it.sceneIndex }.toSet().size > 10)
    }

    @Test fun `one per scene mode uses distinct scenes when enough scenes exist`() {
        val slots = DistributionPlanner.plan(scenes, 40_000, 6, 4, DistributionMode.ONE_PER_SCENE)
        assertEquals(10, slots.size)
        assertEquals(10, slots.map { it.sceneIndex }.toSet().size)
    }

    @Test fun `automatic mode honors scene media preferences without changing quotas`() {
        val alternatingScenes = (0 until 10).map { index ->
            SceneEntity("p", index, index * 1_000L, (index + 1) * 1_000L, 1_000, "fala", "visual", "[]", if (index % 2 == 0) "video" else "image")
        }
        val slots = DistributionPlanner.plan(alternatingScenes, 10_000, 5, 5, DistributionMode.AUTOMATIC)
        assertEquals(5, slots.count { it.mediaKind == MediaKind.VIDEO })
        assertEquals(5, slots.count { it.mediaKind == MediaKind.IMAGE })
        assertTrue(slots.all { slot ->
            val scene = alternatingScenes.single { it.sceneIndex == slot.sceneIndex }
            scene.preferredMedia.equals(slot.mediaKind.name, ignoreCase = true)
        })
    }

    @Test fun `zero media produces no slots`() {
        assertTrue(DistributionPlanner.plan(scenes, 40_000, 0, 0, DistributionMode.AUTOMATIC).isEmpty())
    }
}
