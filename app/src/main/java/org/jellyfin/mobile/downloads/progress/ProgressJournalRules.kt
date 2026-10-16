package org.jellyfin.mobile.downloads.progress

import java.util.UUID

/**
 * The pure state transitions of the offline progress journal. Conflict rule (see
 * docs/sloptank-c203/progress-receipts-design.md section 4.5): an offline operation applies only if the server
 * revision still equals the acknowledged baseline; otherwise the server state wins, the local change is
 * discarded, and the stale operation is never resent with a refreshed baseline.
 */
object ProgressJournalRules {
    /**
     * Coalesces one local playback report. A completion sets played, resets the position and counts once;
     * a later partial position keeps played (an older mid-playback state can never undo a completion).
     */
    fun recordLocal(entry: ProgressEntry, positionTicks: Long, completed: Boolean, nowIso: String): ProgressEntry =
        if (completed) {
            entry.copy(
                localPositionTicks = 0,
                localPlayed = true,
                localCompletions = entry.localCompletions + 1,
                localLastPlayedDate = nowIso,
                dirty = true,
                state = SyncState.PENDING,
            )
        } else {
            entry.copy(
                localPositionTicks = positionTicks.coerceAtLeast(0),
                localPlayed = entry.localPlayed || (entry.baseline?.played == true && !entry.dirty),
                localLastPlayedDate = nowIso,
                dirty = true,
                state = if (entry.state == SyncState.UNSUPPORTED) SyncState.UNSUPPORTED else SyncState.PENDING,
            )
        }

    /**
     * Freezes the open local state into an operation against the acknowledged baseline. Returns null when there
     * is nothing to freeze, an operation is already in flight, or no baseline exists yet.
     */
    fun freeze(entry: ProgressEntry, sequence: Long, operationId: UUID = UUID.randomUUID()): ProgressEntry? {
        val baseline = entry.baseline ?: return null
        if (!entry.dirty || entry.frozen != null) return null
        if (entry.state == SyncState.REJECTED || entry.state == SyncState.UNSUPPORTED) return null
        val operation = FrozenOperation(
            operationId = operationId,
            sequence = sequence,
            baseRevision = baseline.revision,
            positionTicks = entry.localPositionTicks,
            played = entry.localPlayed,
            playCountIncrement = entry.localCompletions.coerceAtMost(MAX_INCREMENT),
            lastPlayedDate = entry.localLastPlayedDate,
        )
        return entry.copy(frozen = operation, dirty = false, localCompletions = 0, state = SyncState.PENDING)
    }

    /** Applies the server's answer to the frozen operation. */
    fun onResult(entry: ProgressEntry, result: ApplyResult): ProgressEntry = when (result) {
        is ApplyResult.Applied -> entry.copy(
            baseline = result.state,
            frozen = null,
            state = if (entry.dirty) SyncState.PENDING else SyncState.CLEAN,
        )
        is ApplyResult.Conflict -> entry.copy(
            // Server wins: adopt its state, drop the frozen and the open local change.
            baseline = result.state,
            frozen = null,
            dirty = false,
            localCompletions = 0,
            localPositionTicks = result.state.positionTicks,
            localPlayed = result.state.played,
            state = SyncState.CONFLICTED,
        )
        is ApplyResult.Refused -> entry.copy(state = SyncState.REJECTED)
        ApplyResult.Unsupported -> entry.copy(state = SyncState.UNSUPPORTED)
        ApplyResult.Gone -> entry.copy(state = SyncState.REJECTED)
    }

    /**
     * Accepts a freshly read server baseline only while nothing local is waiting; with pending local changes
     * the old baseline must stay, so a stale change can never be made to look current.
     */
    fun refreshBaseline(entry: ProgressEntry, serverIdentity: String, progress: ServerProgress): ProgressEntry {
        val hasLocalChanges = entry.dirty || entry.frozen != null
        if (entry.baseline != null && hasLocalChanges) return entry
        if (hasLocalChanges) {
            // Local changes without any acknowledged baseline cannot prove they are newer: the server wins.
            return onResult(entry.copy(serverIdentity = serverIdentity), ApplyResult.Conflict(progress))
        }
        return entry.copy(
            serverIdentity = serverIdentity,
            baseline = progress,
            localPositionTicks = progress.positionTicks,
            localPlayed = progress.played,
            state = if (entry.state == SyncState.CONFLICTED) SyncState.CONFLICTED else SyncState.CLEAN,
        )
    }

    private const val MAX_INCREMENT = 100
}
