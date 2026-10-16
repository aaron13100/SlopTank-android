package org.jellyfin.mobile.downloads.progress

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Removing a server or signing out and then adding the same server back gives the account new local ids. Offline
 * progress kept under the old ids must follow the same server and server-side user to the new account and sync
 * there, exactly once.
 */
class AccountReattachTest {
    private val server = FakeProgressServer("server-1").apply { strictSequences = true }
    private val otherServer = FakeProgressServer("server-2")
    private val store = FakeProgressStore()
    private val reconciler = ProgressReconciler(store)
    private val recorder = ProgressRecorder(store) { "2026-10-16T12:00:00Z" }
    private val itemId = UUID.randomUUID()
    private val user = UUID.randomUUID()

    private val servers = mutableMapOf<Pair<Long, Long>, FakeProgressServer>()
    private val signedIn = mutableListOf<SyncAccount>()
    private val sync = ProgressSync(store, reconciler, { signedIn.toList() }) { serverId, userId ->
        servers[serverId to userId]?.takeIf { (serverId to userId) in store.usableAccounts }
    }

    private fun signIn(serverId: Long, userId: Long, accountUser: UUID = user, to: FakeProgressServer = server) {
        servers[serverId to userId] = to
        store.usableAccounts += serverId to userId
        signedIn += SyncAccount(serverId, userId, accountUser)
    }

    /** Sign-out clears the token: the account row stays but can no longer reach the server. */
    private fun signOut(serverId: Long, userId: Long) {
        store.usableAccounts -= serverId to userId
        signedIn.removeAll { it.serverId == serverId && it.userId == userId }
    }

    /** Removing the server removes its accounts and their downloads (the journal outlives them). */
    private fun removeServer(serverId: Long, userId: Long) = runBlocking {
        signOut(serverId, userId)
        servers.remove(serverId to userId)
        for (entry in store.entries(serverId, userId)) {
            store.removedDownloads += entry.downloadId
            store.purgeIfDetached(entry.downloadId)
        }
    }

    private fun download(id: Long, serverId: Long, userId: Long, item: UUID = itemId, withBaseline: Boolean = true) =
        runBlocking {
            val baseline = if (withBaseline) server.baseline(item) as BaselineResult.Ok else null
            store.entries[id] = newProgressEntry(id, serverId, userId, item, baseline).copy(accountUserId = user)
        }

    private fun complete(id: Long) = runBlocking { recorder.record(id, 0, completed = true) }

    private fun run() = runBlocking { sync.run() }

    @Test
    fun `an offline completion survives removing the server and adding it back`() {
        signIn(1, 1)
        download(1, 1, 1)
        complete(1)
        server.offline = true
        run() shouldBe true

        removeServer(1, 1)
        store.entries[1].shouldNotBeNull()

        server.offline = false
        signIn(2, 2)
        run() shouldBe false

        server.state(itemId).playCount shouldBe 1
        server.writes shouldBe 1
        store.entries[1].shouldBeNull()
        run()
        server.writes shouldBe 1
    }

    @Test
    fun `an operation in flight is replayed by the new account and later operations do not reuse its sequence`() {
        signIn(1, 1)
        download(1, 1, 1)
        complete(1)
        server.loseNextReply = true
        run() shouldBe true
        val inFlight = store.entries.getValue(1).frozen.shouldNotBeNull()

        removeServer(1, 1)
        signIn(2, 2)
        run() shouldBe false

        server.writes shouldBe 1
        store.entries[1].shouldBeNull()

        val secondItem = UUID.randomUUID()
        download(2, 2, 2, item = secondItem)
        complete(2)
        run() shouldBe false

        server.state(secondItem).playCount shouldBe 1
        server.sequences.last() shouldBe inFlight.sequence + 1
    }

    @Test
    fun `signing out and back in through another address moves the kept progress to the new account`() {
        signIn(1, 1)
        download(1, 1, 1)
        signOut(1, 1)
        complete(1)

        signIn(2, 2)
        run() shouldBe false

        server.state(itemId).playCount shouldBe 1
        val entry = store.entries.getValue(1)
        (entry.serverId to entry.userId) shouldBe (2L to 2L)
        entry.state shouldBe SyncState.CLEAN
    }

    @Test
    fun `progress without a baseline is still moved and then resolved by the server`() {
        signIn(1, 1)
        download(1, 1, 1, withBaseline = false)
        complete(1)
        removeServer(1, 1)

        signIn(2, 2)
        run() shouldBe false

        store.entries[1].shouldBeNull()
    }

    @Test
    fun `progress is never moved to another server or another user`() {
        signIn(1, 1)
        download(1, 1, 1)
        complete(1)
        removeServer(1, 1)

        signIn(2, 2, to = otherServer)
        signIn(4, 4, accountUser = UUID.randomUUID())
        run() shouldBe false

        val entry = store.entries.getValue(1)
        (entry.serverId to entry.userId) shouldBe (1L to 1L)
        entry.state shouldBe SyncState.PENDING
        otherServer.writes shouldBe 0
        server.writes shouldBe 0
    }

    @Test
    fun `progress is never moved away from an account that can still sync it`() {
        signIn(1, 1)
        download(1, 1, 1)
        complete(1)

        signIn(2, 2)
        run() shouldBe false

        val entry = store.entries.getValue(1)
        (entry.serverId to entry.userId) shouldBe (1L to 1L)
        server.writes shouldBe 1
    }
}
