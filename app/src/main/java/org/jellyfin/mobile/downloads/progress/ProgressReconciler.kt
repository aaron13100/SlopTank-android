package org.jellyfin.mobile.downloads.progress

import java.util.UUID

/** Durable journal storage (Room in the app). Every method is atomic. */
interface ProgressStore {
    suspend fun entries(serverId: Long, userId: Long): List<ProgressEntry>

    suspend fun entry(downloadId: Long): ProgressEntry?

    /** Reads, transforms and writes one entry atomically; a null result leaves it unchanged. */
    suspend fun update(downloadId: Long, transform: (ProgressEntry) -> ProgressEntry?): ProgressEntry?

    /**
     * Like [update], but also allocates the account's next operation sequence in the same transaction. The
     * sequence is consumed only if [transform] returns a new entry. It follows the highest sequence used on this
     * device by any account of the entry's server and server-side user, because the server counts sequences per
     * user and device and a re-added account must never reuse one.
     */
    suspend fun updateWithSequence(downloadId: Long, transform: (ProgressEntry, Long) -> ProgressEntry?): ProgressEntry?

    /**
     * Deletes the entry only if its download has been removed and the entry is settled (see
     * [ProgressEntry.isSettled]); unsynced progress of a removed download stays until then. Returns true if
     * the entry was deleted.
     */
    suspend fun purgeIfDetached(downloadId: Long): Boolean

    /** Every account (local server and user id) that has journal entries. */
    suspend fun accounts(): List<Pair<Long, Long>>

    /**
     * Gives [account] the entries of the same server ([serverIdentity]) and server-side user that were kept under
     * other local ids no longer able to sync (the server was removed and added back, or the user signed out and
     * in through another address), and labels its own unlabelled entries with its server-side user. Returns how
     * many entries moved; their operations keep their sequences, see [updateWithSequence].
     */
    suspend fun adopt(account: SyncAccount, serverIdentity: String): Int
}

data class ReconcileSummary(
    val applied: Int,
    val replayed: Int,
    val conflicts: Int,
    val refused: Int,
)

/**
 * Sends frozen offline operations for one account and refreshes clean baselines. Transient failures propagate
 * as [java.io.IOException] with the frozen operation kept, so the next attempt resends it byte-identically.
 */
class ProgressReconciler(private val store: ProgressStore) {
    suspend fun reconcile(serverId: Long, userId: Long, api: ProgressApi): ReconcileSummary {
        var summary = ReconcileSummary(0, 0, 0, 0)
        val active = store.entries(serverId, userId).filter { it.state !in INACTIVE }
        for (initial in active) {
            refreshIfNeeded(initial, api)?.let { entry -> summary = send(entry, api, summary) }
        }
        // Entries of removed downloads stay until they are settled; drop the ones that are settled now.
        store.entries(serverId, userId).filter { it.isSettled }.forEach { store.purgeIfDetached(it.downloadId) }
        return summary
    }

    private suspend fun refreshIfNeeded(entry: ProgressEntry, api: ProgressApi): ProgressEntry? {
        val clean = !entry.dirty && entry.frozen == null
        if (entry.baseline != null && !clean) return entry
        return when (val result = api.baseline(entry.itemId)) {
            is BaselineResult.Ok -> store.update(entry.downloadId) { current ->
                ProgressJournalRules.refreshBaseline(current, result.serverIdentity, result.progress)
            }
            BaselineResult.Unsupported -> store.update(entry.downloadId) { current ->
                if (current.dirty) current.copy(state = SyncState.UNSUPPORTED) else null
            }.let { null }
            BaselineResult.Gone -> null
        }
    }

    private suspend fun send(start: ProgressEntry, api: ProgressApi, initial: ReconcileSummary): ReconcileSummary {
        var summary = initial
        var entry = start
        repeat(MAX_OPERATIONS_PER_ENTRY) {
            if (entry.frozen == null) {
                entry = store.updateWithSequence(entry.downloadId) { current, sequence ->
                    ProgressJournalRules.freeze(current, sequence)
                } ?: return summary
            }
            val operation = entry.frozen ?: return summary
            val identity = entry.serverIdentity ?: return summary
            val result = api.apply(entry.itemId, identity, operation)
            entry = store.update(entry.downloadId) { current ->
                // Only the answer to the operation still in flight may change the entry.
                val stillInFlight = current.frozen?.operationId == operation.operationId
                if (stillInFlight) ProgressJournalRules.onResult(current, result) else null
            } ?: return summary
            summary = summary.count(result)
            if (result !is ApplyResult.Applied) return summary
        }
        return summary
    }

    private fun ReconcileSummary.count(result: ApplyResult) = when (result) {
        is ApplyResult.Applied -> if (result.replayed) copy(replayed = replayed + 1) else copy(applied = applied + 1)
        is ApplyResult.Conflict -> copy(conflicts = conflicts + 1)
        else -> copy(refused = refused + 1)
    }

    private companion object {
        const val MAX_OPERATIONS_PER_ENTRY = 3
        val INACTIVE = setOf(SyncState.REJECTED, SyncState.UNSUPPORTED)
    }
}

/** Records local playback of downloads into the journal. Never touches the network. */
class ProgressRecorder(
    private val store: ProgressStore,
    private val clockIso: () -> String,
) {
    /**
     * Records the current position of a downloaded item. [completed] marks a finished playback (played to the
     * end or past the watched threshold); it counts once per call, so callers report completion once.
     */
    suspend fun record(downloadId: Long, positionTicks: Long, completed: Boolean): ProgressEntry? =
        store.update(downloadId) { entry ->
            ProgressJournalRules.recordLocal(entry, positionTicks, completed, clockIso())
        }

    suspend fun resumePositionTicks(downloadId: Long): Long? = store.entry(downloadId)?.resumePositionTicks

    companion object {
        /** Matches the server's default MaxResumePct: at or beyond this fraction an item counts as watched. */
        const val WATCHED_FRACTION = 0.9

        /** Below this fraction the server would not keep a resume position either. */
        const val MIN_RESUME_FRACTION = 0.05

        fun isWatched(positionTicks: Long, runtimeTicks: Long, ended: Boolean): Boolean =
            ended || (runtimeTicks > 0 && positionTicks >= runtimeTicks * WATCHED_FRACTION)

        fun resumeTicks(positionTicks: Long, runtimeTicks: Long): Long =
            if (runtimeTicks > 0 && positionTicks < runtimeTicks * MIN_RESUME_FRACTION) 0 else positionTicks
    }
}

/** Creates the journal entry of a new download from its baseline, if one could be read. */
fun newProgressEntry(downloadId: Long, serverId: Long, userId: Long, itemId: UUID, baseline: BaselineResult.Ok?) =
    ProgressEntry(
        downloadId = downloadId,
        serverId = serverId,
        userId = userId,
        itemId = itemId,
        serverIdentity = baseline?.serverIdentity,
        baseline = baseline?.progress,
        localPositionTicks = baseline?.progress?.positionTicks ?: 0,
        localPlayed = baseline?.progress?.played ?: false,
    )
