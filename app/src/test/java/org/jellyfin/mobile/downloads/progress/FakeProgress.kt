package org.jellyfin.mobile.downloads.progress

import java.io.IOException
import java.util.UUID

/**
 * A fake of the server's conditional progress endpoint with the same semantics as the server implementation:
 * a revision per item, receipts keyed by operation id with payload comparison, conflict on a stale baseline.
 */
class FakeProgressServer(private val identity: String = "server-1") : ProgressApi {
    private data class Receipt(val operation: FrozenOperation, val itemId: UUID, val result: ApplyResult)

    val states = mutableMapOf<UUID, ServerProgress>()
    private val receipts = mutableMapOf<UUID, Receipt>()
    var writes = 0
        private set

    /** Fails the next apply after committing it, like a reply lost after the server's commit. */
    var loseNextReply = false

    /** Fails every request before reaching the server, like airplane mode. */
    var offline = false

    var supported = true

    fun state(itemId: UUID) = states.getOrPut(itemId) { ServerProgress(0, 0, false, 0) }

    /** Online playback on another device: always advances the revision. */
    fun onlinePlayback(itemId: UUID, positionTicks: Long) {
        val current = state(itemId)
        states[itemId] = current.copy(revision = current.revision + 1, positionTicks = positionTicks)
    }

    override suspend fun baseline(itemId: UUID): BaselineResult {
        if (offline) throw IOException("offline")
        if (!supported) return BaselineResult.Unsupported
        return BaselineResult.Ok(identity, state(itemId))
    }

    override suspend fun apply(itemId: UUID, serverIdentity: String, operation: FrozenOperation): ApplyResult {
        if (offline) throw IOException("offline")
        if (!supported) return ApplyResult.Unsupported
        if (serverIdentity != identity) return ApplyResult.Refused("wrong server")
        receipts[operation.operationId]?.let { receipt ->
            val samePayload = receipt.operation == operation && receipt.itemId == itemId
            if (!samePayload) return ApplyResult.Refused("payload mismatch")
            return when (val recorded = receipt.result) {
                is ApplyResult.Applied -> recorded.copy(replayed = true)
                else -> recorded
            }
        }
        val current = state(itemId)
        val result = if (current.revision != operation.baseRevision) {
            ApplyResult.Conflict(current)
        } else {
            writes++
            val next = ServerProgress(
                revision = current.revision + 1,
                positionTicks = operation.positionTicks,
                played = operation.played,
                playCount = current.playCount + operation.playCountIncrement,
            )
            states[itemId] = next
            ApplyResult.Applied(next, replayed = false)
        }
        receipts[operation.operationId] = Receipt(operation, itemId, result)
        if (loseNextReply) {
            loseNextReply = false
            throw IOException("reply lost after commit")
        }
        return result
    }
}

/** In-memory journal store (the Room database is the platform boundary). */
class FakeProgressStore : ProgressStore {
    val entries = mutableMapOf<Long, ProgressEntry>()
    private val sequences = mutableMapOf<Pair<Long, Long>, Long>()

    override suspend fun entries(serverId: Long, userId: Long) =
        entries.values.filter { it.serverId == serverId && it.userId == userId }.sortedBy { it.downloadId }

    override suspend fun entry(downloadId: Long) = entries[downloadId]

    override suspend fun update(downloadId: Long, transform: (ProgressEntry) -> ProgressEntry?): ProgressEntry? {
        val current = entries[downloadId] ?: return null
        val next = transform(current) ?: return null
        entries[downloadId] = next
        return next
    }

    override suspend fun updateWithSequence(
        downloadId: Long,
        transform: (ProgressEntry, Long) -> ProgressEntry?,
    ): ProgressEntry? {
        val current = entries[downloadId] ?: return null
        val key = current.serverId to current.userId
        val sequence = (sequences[key] ?: 0) + 1
        val next = transform(current, sequence) ?: return null
        sequences[key] = sequence
        entries[downloadId] = next
        return next
    }
}
