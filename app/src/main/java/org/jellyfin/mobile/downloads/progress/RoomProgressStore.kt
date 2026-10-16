package org.jellyfin.mobile.downloads.progress

import androidx.room.withTransaction
import org.jellyfin.mobile.data.JellyfinDatabase
import org.jellyfin.mobile.data.dao.ProgressDao
import org.jellyfin.mobile.data.entity.OfflineProgressEntity
import org.jellyfin.mobile.data.entity.ProgressSequenceEntity

/** [ProgressStore] in Room; updates run in a database transaction. */
class RoomProgressStore(
    private val database: JellyfinDatabase,
    private val dao: ProgressDao = database.progressDao,
) : ProgressStore {
    override suspend fun entries(serverId: Long, userId: Long) = dao.getEntries(serverId, userId).map { it.toEntry() }

    override suspend fun entry(downloadId: Long) = dao.getEntry(downloadId)?.toEntry()

    override suspend fun update(downloadId: Long, transform: (ProgressEntry) -> ProgressEntry?): ProgressEntry? =
        database.withTransaction {
            val current = dao.getEntry(downloadId)?.toEntry() ?: return@withTransaction null
            transform(current)?.also { dao.upsert(it.toEntity()) }
        }

    override suspend fun updateWithSequence(
        downloadId: Long,
        transform: (ProgressEntry, Long) -> ProgressEntry?,
    ): ProgressEntry? = database.withTransaction {
        val current = dao.getEntry(downloadId)?.toEntry() ?: return@withTransaction null
        val sequence = (dao.getLastSequence(current.serverId, current.userId) ?: 0) + 1
        transform(current, sequence)?.also { next ->
            dao.setLastSequence(ProgressSequenceEntity(current.serverId, current.userId, sequence))
            dao.upsert(next.toEntity())
        }
    }

    override suspend fun purgeIfDetached(downloadId: Long): Boolean = dao.purgeIfDetached(downloadId) == 1

    /** Creates the journal entry of a new download; an existing entry is kept. */
    suspend fun create(entry: ProgressEntry) {
        dao.insertIfAbsent(entry.toEntity())
    }

    suspend fun accounts(): List<Pair<Long, Long>> = dao.getAccounts().map { it.serverId to it.userId }
}

private fun OfflineProgressEntity.toEntry() = ProgressEntry(
    downloadId = downloadId,
    serverId = serverId,
    userId = userId,
    itemId = itemId,
    serverIdentity = serverIdentity,
    baseline = baseRevision?.let { revision ->
        ServerProgress(revision, basePositionTicks ?: 0, basePlayed ?: false, basePlayCount ?: 0)
    },
    localPositionTicks = localPositionTicks,
    localPlayed = localPlayed,
    localCompletions = localCompletions,
    localLastPlayedDate = localLastPlayed,
    dirty = dirty,
    frozen = operationId?.let { id ->
        FrozenOperation(
            operationId = id,
            sequence = operationSequence ?: 0,
            baseRevision = operationBaseRevision ?: 0,
            positionTicks = operationPositionTicks ?: 0,
            played = operationPlayed ?: false,
            playCountIncrement = operationPlayCountIncrement ?: 0,
            lastPlayedDate = operationLastPlayed,
        )
    },
    state = SyncState.valueOf(state),
)

private fun ProgressEntry.toEntity() = OfflineProgressEntity(
    downloadId = downloadId,
    serverId = serverId,
    userId = userId,
    itemId = itemId,
    serverIdentity = serverIdentity,
    baseRevision = baseline?.revision,
    basePositionTicks = baseline?.positionTicks,
    basePlayed = baseline?.played,
    basePlayCount = baseline?.playCount,
    localPositionTicks = localPositionTicks,
    localPlayed = localPlayed,
    localCompletions = localCompletions,
    localLastPlayed = localLastPlayedDate,
    dirty = dirty,
    operationId = frozen?.operationId,
    operationSequence = frozen?.sequence,
    operationBaseRevision = frozen?.baseRevision,
    operationPositionTicks = frozen?.positionTicks,
    operationPlayed = frozen?.played,
    operationPlayCountIncrement = frozen?.playCountIncrement,
    operationLastPlayed = frozen?.lastPlayedDate,
    state = state.name,
)
