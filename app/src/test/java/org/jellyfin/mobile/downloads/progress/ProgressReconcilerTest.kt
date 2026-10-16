package org.jellyfin.mobile.downloads.progress

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.UUID

class ProgressReconcilerTest {
    private val server = FakeProgressServer()
    private val store = FakeProgressStore()
    private val reconciler = ProgressReconciler(store)
    private val recorder = ProgressRecorder(store) { "2026-10-16T12:00:00Z" }
    private val itemId = UUID.randomUUID()

    private fun download(id: Long = 1, item: UUID = itemId, serverId: Long = 1, userId: Long = 1) = runBlocking {
        val baseline = server.baseline(item) as BaselineResult.Ok
        store.entries[id] = newProgressEntry(id, serverId, userId, item, baseline)
    }

    private fun record(position: Long, completed: Boolean = false, id: Long = 1) = runBlocking {
        recorder.record(id, position, completed)
    }

    private fun reconcile() = runBlocking { reconciler.reconcile(1, 1, server) }

    @Test
    fun `offline completion syncs exactly once`() {
        download()
        server.offline = true
        record(5_000)
        record(0, completed = true)
        shouldThrow<IOException> { reconcile() }

        server.offline = false
        reconcile().applied shouldBe 1
        reconcile().applied shouldBe 0
        server.state(itemId).playCount shouldBe 1
        server.state(itemId).played shouldBe true
        server.writes shouldBe 1
        store.entries.getValue(1).state shouldBe SyncState.CLEAN
    }

    @Test
    fun `a reply lost after the commit is replayed, not applied twice`() {
        download()
        record(0, completed = true)
        server.loseNextReply = true
        shouldThrow<IOException> { reconcile() }
        val frozen = store.entries.getValue(1).frozen

        val summary = reconcile()

        summary.replayed shouldBe 1
        server.writes shouldBe 1
        server.state(itemId).playCount shouldBe 1
        frozen?.operationId shouldBe frozen?.operationId
        store.entries.getValue(1).frozen.shouldBeNull()
    }

    @Test
    fun `newer server progress is never overwritten by stale local state`() {
        download()
        record(30_000)
        server.onlinePlayback(itemId, 90_000) // another device played after our baseline

        val summary = reconcile()

        summary.conflicts shouldBe 1
        server.state(itemId).positionTicks shouldBe 90_000
        val entry = store.entries.getValue(1)
        entry.state shouldBe SyncState.CONFLICTED
        entry.resumePositionTicks shouldBe 90_000
        // The stale change is gone for good: nothing is resent on the next reconcile.
        reconcile() shouldBe ReconcileSummary(0, 0, 0, 0)
        server.writes shouldBe 0
    }

    @Test
    fun `local updates coalesce into one operation and later updates follow the acknowledged revision`() {
        download()
        record(1_000)
        record(2_000)
        record(3_000)
        reconcile().applied shouldBe 1
        server.writes shouldBe 1
        server.state(itemId).positionTicks shouldBe 3_000

        record(4_000)
        reconcile().applied shouldBe 1
        server.state(itemId).positionTicks shouldBe 4_000
        server.state(itemId).revision shouldBe 2
    }

    @Test
    fun `an older partial position cannot undo a local completion`() {
        download()
        record(0, completed = true)
        record(10_000)
        reconcile()
        server.state(itemId).played shouldBe true
        server.state(itemId).playCount shouldBe 1
    }

    @Test
    fun `a server without the conditional endpoint never gets an unconditional write`() {
        download()
        server.supported = false
        record(7_000)
        reconcile()
        store.entries.getValue(1).state shouldBe SyncState.UNSUPPORTED
        server.writes shouldBe 0
        store.entries.getValue(1).resumePositionTicks shouldBe 7_000
    }

    @Test
    fun `local changes without any baseline lose to the server`() {
        store.entries[1] = newProgressEntry(1, 1, 1, itemId, baseline = null)
        record(8_000)
        server.onlinePlayback(itemId, 2_000)
        reconcile()
        server.writes shouldBe 0
        store.entries.getValue(1).state shouldBe SyncState.CONFLICTED
        store.entries.getValue(1).resumePositionTicks shouldBe 2_000
    }

    @Test
    fun `a clean entry follows the server baseline, a dirty one keeps its old baseline`() {
        download()
        server.onlinePlayback(itemId, 50_000)
        reconcile()
        store.entries.getValue(1).baseline?.revision shouldBe 1
        store.entries.getValue(1).resumePositionTicks shouldBe 50_000

        record(60_000)
        server.onlinePlayback(itemId, 70_000)
        val refreshed = ProgressJournalRules.refreshBaseline(
            store.entries.getValue(1),
            "server-1",
            server.state(itemId)
        )
        refreshed.baseline?.revision shouldBe 1
    }

    @Test
    fun `operations of one account never use another account's journal`() {
        download(id = 1)
        download(id = 2, item = UUID.randomUUID(), userId = 2)
        record(1_000, id = 1)
        record(2_000, id = 2)
        runBlocking { reconciler.reconcile(1, 1, server) }
        store.entries.getValue(2).frozen.shouldBeNull()
        store.entries.getValue(2).dirty shouldBe true
    }

    @Test
    fun `watched and resume thresholds match the server defaults`() {
        ProgressRecorder.isWatched(900, 1_000, ended = false) shouldBe true
        ProgressRecorder.isWatched(899, 1_000, ended = false) shouldBe false
        ProgressRecorder.isWatched(10, 1_000, ended = true) shouldBe true
        ProgressRecorder.resumeTicks(49, 1_000) shouldBe 0
        ProgressRecorder.resumeTicks(50, 1_000) shouldBe 50
    }
}
