package ai.autovideo.app.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

object ProjectWorkCoordinator {
    fun enqueueProcessing(context: Context, projectId: String) {
        val request = OneTimeWorkRequestBuilder<ProjectPipelineWorker>()
            .setInputData(workDataOf(ProjectPipelineWorker.KEY_PROJECT_ID to projectId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(tag(projectId))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(processingName(projectId), ExistingWorkPolicy.KEEP, request)
    }

    fun enqueueRender(context: Context, projectId: String, preview: Boolean) {
        val request = OneTimeWorkRequestBuilder<ProjectRenderWorker>()
            .setInputData(workDataOf(
                ProjectRenderWorker.KEY_PROJECT_ID to projectId,
                ProjectRenderWorker.KEY_PREVIEW to preview
            ))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .addTag(tag(projectId))
            .build()
        val kind = if (preview) "preview" else "render"
        WorkManager.getInstance(context).enqueueUniqueWork("$kind:$projectId", ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(context: Context, projectId: String) {
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork(processingName(projectId))
        manager.cancelUniqueWork("preview:$projectId")
        manager.cancelUniqueWork("render:$projectId")
    }

    private fun processingName(projectId: String) = "processing:$projectId"
    private fun tag(projectId: String) = "project:$projectId"
}
