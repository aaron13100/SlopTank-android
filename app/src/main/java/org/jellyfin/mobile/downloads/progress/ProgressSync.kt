package org.jellyfin.mobile.downloads.progress

import timber.log.Timber
import java.io.IOException
import java.util.UUID

/** A stored, signed-in account: its local ids and its server-side user id. */
data class SyncAccount(val serverId: Long, val userId: Long, val accountUserId: UUID)

/**
 * One progress sync pass over every account. Kept free of Android types so it is tested as a whole; the worker
 * supplies the signed-in accounts and their progress APIs.
 */
class ProgressSync(
    private val store: ProgressStore,
    private val reconciler: ProgressReconciler,
    private val signedInAccounts: suspend () -> List<SyncAccount>,
    private val apiFor: (serverId: Long, userId: Long) -> ProgressApi?,
) {
    /** Returns true if a transient failure needs another attempt. */
    suspend fun run(beforeReconcile: suspend (serverId: Long, userId: Long) -> Unit = { _, _ -> }): Boolean {
        var retry = adoptKeptEntries()
        for ((serverId, userId) in store.accounts()) {
            // A logged-out or removed account keeps its journal until it signs in again.
            val api = apiFor(serverId, userId) ?: continue
            beforeReconcile(serverId, userId)
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
        return retry
    }

    /**
     * Gives every signed-in account the entries kept from a removed or signed-out account of the same server and
     * server-side user, so progress made before the server was removed or the user signed out is still synced.
     */
    private suspend fun adoptKeptEntries(): Boolean {
        var retry = false
        for (account in signedInAccounts()) {
            val api = apiFor(account.serverId, account.userId) ?: continue
            try {
                val moved = store.adopt(account, api.serverIdentity())
                if (moved > 0) Timber.i("Moved %d kept progress entries to account %s", moved, account)
            } catch (e: ProgressAuthException) {
                Timber.w(e, "Account %s needs a new sign-in before it can take kept progress", account)
            } catch (e: IOException) {
                Timber.w(e, "Server of account %s unreachable; kept progress moves on the next attempt", account)
                retry = true
            }
        }
        return retry
    }
}
