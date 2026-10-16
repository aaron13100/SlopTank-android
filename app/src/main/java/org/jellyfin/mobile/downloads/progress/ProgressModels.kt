package org.jellyfin.mobile.downloads.progress

import java.util.UUID

/** Server playback state together with its server-owned revision (the offline baseline). */
data class ServerProgress(
    val revision: Long,
    val positionTicks: Long,
    val played: Boolean,
    val playCount: Int,
)

/** An operation that has been (or is being) sent. It never changes, so every retry is byte-identical. */
data class FrozenOperation(
    val operationId: UUID,
    val sequence: Long,
    val baseRevision: Long,
    val positionTicks: Long,
    val played: Boolean,
    val playCountIncrement: Int,
    /** ISO-8601 UTC instant of the last local play, if any. */
    val lastPlayedDate: String?,
)

enum class SyncState {
    /** Nothing local is waiting to be sent. */
    CLEAN,

    /** Local progress is waiting to be sent (open state, frozen operation or both). */
    PENDING,

    /** The server had newer progress; the local change was discarded in favour of it. */
    CONFLICTED,

    /** The server refused the operation as malformed or expired; it is kept for inspection, not resent. */
    REJECTED,

    /** The server has no conditional progress endpoint; local progress is kept and never force-written. */
    UNSUPPORTED,
}

/**
 * The offline progress journal entry of one download (one account and item). The open local state coalesces
 * every local update; at most one frozen operation exists at a time.
 */
data class ProgressEntry(
    val downloadId: Long,
    val serverId: Long,
    val userId: Long,
    val itemId: UUID,
    /** The server's own id from the baseline response; operations are bound to it. */
    val serverIdentity: String?,
    /** Last acknowledged server state, or null while no baseline has been captured. */
    val baseline: ServerProgress?,
    val localPositionTicks: Long = 0,
    val localPlayed: Boolean = false,
    /** Completed playbacks recorded locally and not yet frozen into an operation. */
    val localCompletions: Int = 0,
    val localLastPlayedDate: String? = null,
    /** True while the open local state has changes that are not frozen yet. */
    val dirty: Boolean = false,
    val frozen: FrozenOperation? = null,
    val state: SyncState = SyncState.CLEAN,
) {
    /** Nothing local is waiting: the server acknowledged it, or a conflict was resolved in its favour. */
    val isSettled: Boolean
        get() = !dirty && frozen == null && (state == SyncState.CLEAN || state == SyncState.CONFLICTED)

    /** Where offline playback resumes: the newest local state, else the acknowledged server state. */
    val resumePositionTicks: Long
        get() = when {
            dirty || frozen != null -> localPositionTicks
            else -> baseline?.positionTicks ?: localPositionTicks
        }
}

sealed interface BaselineResult {
    data class Ok(val serverIdentity: String, val progress: ServerProgress) : BaselineResult

    /** The server has no conditional progress endpoint (an older server). */
    data object Unsupported : BaselineResult

    /** The item no longer exists or is not visible to the user. */
    data object Gone : BaselineResult
}

sealed interface ApplyResult {
    data class Applied(val state: ServerProgress, val replayed: Boolean) : ApplyResult
    data class Conflict(val state: ServerProgress) : ApplyResult
    data class Refused(val reason: String) : ApplyResult
    data object Unsupported : ApplyResult
    data object Gone : ApplyResult
}

/** The server boundary. Implementations throw [java.io.IOException] for transient failures. */
interface ProgressApi {
    suspend fun baseline(itemId: UUID): BaselineResult

    suspend fun apply(itemId: UUID, serverIdentity: String, operation: FrozenOperation): ApplyResult
}
