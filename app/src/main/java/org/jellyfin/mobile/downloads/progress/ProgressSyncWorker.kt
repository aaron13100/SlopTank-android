package org.jellyfin.mobile.downloads.progress

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import okhttp3.OkHttpClient
import org.jellyfin.mobile.app.ApiClientController
import org.jellyfin.mobile.downloads.CatalogRefresher
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Reconciles the offline progress journal of every account once a network is available. Survives process death
 * (WorkManager); a transient failure retries with backoff and resends the same frozen operations.
 */
class ProgressSyncWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters), KoinComponent {
    private val store by inject<RoomProgressStore>()
    private val reconciler by inject<ProgressReconciler>()
    private val apiFactory by inject<ProgressApiFactory>()
    private val catalogRefresher by inject<CatalogRefresher>()
    private val apiClientController by inject<ApiClientController>()

    override suspend fun doWork(): Result {
        var retry = false
        for ((serverId, userId) in store.accounts()) {
            // A logged-out or removed account keeps its journal until it signs in again.
            val api = apiFactory.create(serverId, userId) ?: continue
            apiClientController.getApiClientOrNull(
                serverId,
                userId
            )?.let { catalogRefresher.refresh(serverId, userId, it) }
            try {
                val summary = reconciler.reconcile(serverId, userId, api)
                Timber.i("Progress sync for account %d/%d: %s", serverId, userId, summary)
            } catch (e: ProgressAuthException) {
                // Expired credentials: keep the journal; the next sign-in schedules another sync.
                Timber.w(e, "Progress sync for account %d/%d needs a new sign-in", serverId, userId)
            } catch (e: IOException) {
                Timber.w(e, "Progress sync for account %d/%d will be retried", serverId, userId)
                retry = true
            }
        }
        return if (retry) Result.retry() else Result.success()
    }

    companion object {
        private const val WORK_NAME = "offline-progress-sync"
        private const val BACKOFF_SECONDS = 30L

        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<ProgressSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}

/** Builds the progress API of one stored account with that account's own credentials and device id. */
class ProgressApiFactory(
    private val apiClientController: ApiClientController,
    private val okHttpClient: OkHttpClient,
) {
    fun create(serverId: Long, userId: Long): ProgressApi? {
        val api = apiClientController.getApiClientOrNull(serverId, userId) ?: return null
        val baseUrl = api.baseUrl ?: return null
        val authorization = AuthorizationHeaderBuilder.buildHeader(
            clientName = api.clientInfo.name,
            clientVersion = api.clientInfo.version,
            deviceId = api.deviceInfo.id,
            deviceName = api.deviceInfo.name,
            accessToken = api.accessToken,
        )
        return HttpProgressApi(okHttpClient, baseUrl, authorization, api.deviceInfo.id)
    }
}
