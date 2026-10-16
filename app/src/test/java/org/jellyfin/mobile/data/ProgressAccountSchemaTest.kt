package org.jellyfin.mobile.data

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.sql.Connection

/**
 * The database side of moving kept journal entries to a re-added account, on real SQLite with the exported
 * schemas: entries and sequence counters carry the server identity and server-side user, and only entries whose
 * old account can no longer sync move.
 */
class ProgressAccountSchemaTest {
    private val connections = mutableListOf<Connection>()

    @AfterEach
    fun close() = connections.forEach(Connection::close)

    private fun open(): Connection = openSqlite().also { connections += it }

    private fun Connection.server(id: Int) =
        sql("INSERT INTO server (id, hostname, last_used_timestamp) VALUES ($id, 'http://host-$id', 0)")

    private fun Connection.user(id: Int, serverId: Int, uuid: String, token: String?) = sql(
        "INSERT INTO user (id, server_id, user_id, access_token, last_login_timestamp) " +
            "VALUES ($id, $serverId, '$uuid', ${token?.let { "'$it'" } ?: "NULL"}, 0)",
    )

    private fun Connection.entry(id: Int, account: Int, identity: String?, accountUser: String? = null) = sql(
        "INSERT INTO offline_progress (download_id, server_id, user_id, item_id, server_identity, " +
            "local_position_ticks, local_played, local_completions, dirty, state" +
            (if (accountUser != null) ", account_user_id" else "") + ") VALUES ($id, $account, $account, 'item-$id', " +
            "${identity?.let { "'$it'" } ?: "NULL"}, 0, 0, 1, 1, 'PENDING'" +
            (if (accountUser != null) ", '$accountUser'" else "") + ")",
    )

    private fun Connection.owners() =
        rows("SELECT download_id, server_id, user_id, account_user_id FROM offline_progress ORDER BY download_id")

    private fun Connection.adopt(account: Int, uuid: String, identity: String) {
        val parameters = arrayOf(
            "serverId" to account,
            "userId" to account,
            "accountUserId" to uuid,
            "serverIdentity" to identity,
        )
        sql(ProgressJournalSchema.FILL_ACCOUNT_USER.bind(*parameters))
        sql(ProgressJournalSchema.ADOPT_ENTRIES.bind(*parameters))
    }

    @Test
    fun `migrating to version 9 gives exactly the exported version 9 schema`() {
        val migrated = open().apply { create(8) }.apply { ProgressJournalSchema.MIGRATION_8_9.forEach { sql(it) } }
        val fresh = open().apply { create(9) }

        for (table in listOf("offline_progress", "progress_sequence")) {
            for (pragma in listOf("table_info", "foreign_key_list", "index_list")) {
                val query = "SELECT name, type, \"notnull\", pk FROM pragma_$pragma('$table') ORDER BY name"
                    .takeIf { pragma == "table_info" } ?: "PRAGMA $pragma($table)"
                migrated.rows(query) shouldBe fresh.rows(query)
            }
        }
    }

    @Test
    fun `migrating to version 9 labels entries and sequences with their server-side user and server`() {
        val db = open().apply { create(8) }
        db.server(1)
        db.user(1, 1, "user-a", "t")
        db.entry(1, account = 1, identity = "sid")
        db.entry(2, account = 7, identity = null)
        db.sql("INSERT INTO progress_sequence (server_id, user_id, last_sequence) VALUES (1, 1, 4), (7, 7, 2)")

        ProgressJournalSchema.MIGRATION_8_9.forEach { db.sql(it) }

        db.owners() shouldBe listOf(listOf("1", "1", "1", "user-a"), listOf("2", "7", "7", null))
        db.rows("SELECT server_id, server_identity, account_user_id FROM progress_sequence ORDER BY server_id") shouldBe
            listOf(listOf("1", "sid", "user-a"), listOf("7", null, null))
    }

    @Test
    fun `only entries of the same server and user whose account can no longer sync move`() {
        val db = open().apply { create(9) }
        listOf(1, 2, 4).forEach { db.server(it) }
        db.user(1, 1, "user-a", token = null) // signed out on the old address
        db.user(2, 2, "user-a", token = "t") // the new account
        db.user(4, 4, "user-a", token = "t") // another address, still signed in: keeps its own entries
        db.entry(1, account = 1, identity = "sid", accountUser = "user-a")
        db.entry(2, account = 9, identity = null, accountUser = "user-a") // server removed, never baselined
        db.entry(3, account = 9, identity = "other-sid", accountUser = "user-a")
        db.entry(4, account = 9, identity = "sid", accountUser = "user-b")
        db.entry(5, account = 4, identity = "sid", accountUser = "user-a")
        db.entry(6, account = 2, identity = null)

        db.adopt(2, "user-a", "sid")

        db.owners() shouldBe listOf(
            listOf("1", "2", "2", "user-a"),
            listOf("2", "2", "2", "user-a"),
            listOf("3", "9", "9", "user-a"),
            listOf("4", "9", "9", "user-b"),
            listOf("5", "4", "4", "user-a"),
            listOf("6", "2", "2", "user-a"),
        )
    }

    @Test
    fun `an account continues the highest sequence its server-side user reached on this device`() {
        val db = open().apply { create(9) }
        db.sql(
            "INSERT INTO progress_sequence (server_id, user_id, last_sequence, server_identity, account_user_id) " +
                "VALUES (1, 1, 5, 'sid', 'user-a'), (4, 4, 7, 'sid', 'user-a'), (5, 5, 9, 'other-sid', 'user-a'), " +
                "(6, 6, 11, 'sid', 'user-b'), (2, 2, 3, 'sid', 'user-a')",
        )

        fun last(account: Int, identity: String?, uuid: String?) = db.rows(
            ProgressJournalSchema.LAST_SEQUENCE.bind(
                "serverId" to account,
                "userId" to account,
                "serverIdentity" to identity,
                "accountUserId" to uuid,
            ),
        ).single().single()

        last(2, "sid", "user-a") shouldBe "7"
        last(3, "sid", "user-a") shouldBe "7"
        last(2, null, null) shouldBe "3"
        last(3, null, null) shouldBe null
    }
}
