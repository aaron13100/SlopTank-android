package org.jellyfin.mobile.downloads.progress

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.UUID

/** Removing a download must never lose offline progress that the server has not acknowledged yet. */
class DownloadRemovalProgressTest {
    private val server = FakeProgressServer()
    private val store = FakeProgressStore()
    private val reconciler = ProgressReconciler(store)
    private val recorder = ProgressRecorder(store) { "2026-10-16T12:00:00Z" }
    private val itemId = UUID.randomUUID()

    private fun download(id: Long = 1, item: UUID = itemId) = runBlocking {
        val baseline = server.baseline(item) as BaselineResult.Ok
        store.entries[id] = newProgressEntry(id, 1, 1, item, baseline)
    }

    /** What DownloadManager.remove does to the journal: the download row goes, then settled entries are purged. */
    private fun removeDownload(id: Long = 1) = runBlocking {
        store.removedDownloads += id
        store.purgeIfDetached(id)
    }

    private fun reconcile() = runBlocking { reconciler.reconcile(1, 1, server) }

    @Test
    fun `removing a download keeps its unsynced completion until the server acknowledges it`() {
        download()
        server.offline = true
        runBlocking { recorder.record(1, 0, completed = true) }

        removeDownload() shouldBe false
        store.entries[1].shouldNotBeNull().state shouldBe SyncState.PENDING

        server.offline = false
        reconcile().applied shouldBe 1

        server.state(itemId).playCount shouldBe 1
        server.writes shouldBe 1
        store.entries[1].shouldBeNull()
        reconcile().applied shouldBe 0
        server.writes shouldBe 1
    }

    @Test
    fun `a frozen operation of a removed download is replayed, not lost`() {
        download()
        runBlocking { recorder.record(1, 0, completed = true) }
        server.loseNextReply = true
        runCatching { reconcile() }
        store.entries.getValue(1).frozen.shouldNotBeNull()

        removeDownload() shouldBe false
        store.entries[1].shouldNotBeNull()

        reconcile().replayed shouldBe 1
        server.writes shouldBe 1
        store.entries[1].shouldBeNull()
    }

    @Test
    fun `a removed download whose change lost a conflict is purged once resolved`() {
        download()
        runBlocking { recorder.record(1, 5_000, completed = false) }
        server.onlinePlayback(itemId, 9_000)

        removeDownload() shouldBe false

        reconcile().conflicts shouldBe 1
        server.state(itemId).positionTicks shouldBe 9_000
        store.entries[1].shouldBeNull()
    }

    @Test
    fun `a removed download with nothing to sync is purged immediately`() {
        download()

        removeDownload() shouldBe true

        store.entries[1].shouldBeNull()
    }

    @Test
    fun `a refused operation of a removed download is kept for inspection`() {
        download()
        runBlocking { recorder.record(1, 5_000, completed = false) }
        store.entries[1] = store.entries.getValue(1).copy(serverIdentity = "another-server")
        reconcile().refused shouldBe 1

        removeDownload() shouldBe false
        reconcile()

        store.entries[1].shouldNotBeNull().state shouldBe SyncState.REJECTED
    }

    @Test
    fun `a download that still exists is never purged`() {
        download()

        runBlocking { store.purgeIfDetached(1) } shouldBe false
        reconcile()

        store.entries[1].shouldNotBeNull()
    }
}
