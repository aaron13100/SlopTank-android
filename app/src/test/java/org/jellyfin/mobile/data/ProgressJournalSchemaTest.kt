package org.jellyfin.mobile.data

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/**
 * The journal's database rules, run on real SQLite with the schemas Room exports: unsynced progress survives
 * the removal of its download and cannot be deleted at all until it is acknowledged or resolved as a conflict.
 */
class ProgressJournalSchemaTest {
    private val connections = mutableListOf<Connection>()

    @AfterEach
    fun close() = connections.forEach(Connection::close)

    private fun open(): Connection = DriverManager.getConnection("jdbc:sqlite::memory:").also { db ->
        connections += db
        db.sql("PRAGMA foreign_keys = ON")
    }

    private fun schema(version: Int): JsonObject {
        val file = File("schemas/org.jellyfin.mobile.data.JellyfinDatabase/$version.json")
        return Json.parseToJsonElement(file.readText()).jsonObject.getValue("database").jsonObject
    }

    /** Creates the tables and indices of an exported schema, as Room does on a fresh install. */
    private fun Connection.create(version: Int) {
        for (entity in schema(version).getValue("entities").jsonArray.map { it.jsonObject }) {
            val table = entity.getValue("tableName").jsonPrimitive.content
            val statements = listOf(entity.getValue("createSql")) +
                (entity["indices"] as JsonArray? ?: JsonArray(emptyList())).map { it.jsonObject.getValue("createSql") }
            statements.forEach { sql(it.jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
        }
    }

    private fun Connection.sql(sql: String) = createStatement().use { it.execute(sql) }

    private fun Connection.rows(sql: String): List<List<String?>> = createStatement().use { statement ->
        statement.executeQuery(sql).use { result ->
            val columns = result.metaData.columnCount
            buildList { while (result.next()) add((1..columns).map { result.getString(it) }) }
        }
    }

    private fun Connection.journalIds() = rows("SELECT download_id FROM offline_progress ORDER BY download_id")
        .map { it.single() }

    private fun Connection.purge(downloadId: Long) =
        sql(ProgressJournalSchema.PURGE_IF_DETACHED.replace(":downloadId", downloadId.toString()))

    private fun Connection.seed() {
        sql("INSERT INTO server (id, hostname, last_used_timestamp) VALUES (1, 'http://server', 0)")
        sql("INSERT INTO user (id, server_id, user_id, access_token, last_login_timestamp) VALUES (1, 1, 'u', 't', 0)")
        for (id in 1..3) {
            sql(
                "INSERT INTO download (id, server_id, user_id, item_id, path, item, status, created_at, modified_at) " +
                    "VALUES ($id, 1, 1, 'item-$id', '', '{}', 'DOWNLOADED', 0, 0)",
            )
        }
        // 1: unsynced local change, 2: frozen operation in flight, 3: nothing to sync.
        journal(1, dirty = true, operationId = null, state = "PENDING")
        journal(2, dirty = false, operationId = "'op-2'", state = "PENDING")
        journal(3, dirty = false, operationId = null, state = "CLEAN")
    }

    private fun Connection.journal(
        id: Int,
        dirty: Boolean,
        operationId: String?,
        state: String,
        verb: String = "INSERT",
    ) = sql(
        "$verb INTO offline_progress (download_id, server_id, user_id, item_id, local_position_ticks, " +
            "local_played, local_completions, dirty, op_id, state) " +
            "VALUES ($id, 1, 1, 'item-$id', 0, 0, 1, ${if (dirty) 1 else 0}, ${operationId ?: "NULL"}, '$state')",
    )

    private fun Connection.guard() = ProgressJournalSchema.OPEN.forEach { sql(it) }

    /** A fresh version 8 database with its guard and the seeded journal. */
    private fun seeded() = open().apply { create(8) }.apply { guard() }.apply { seed() }

    private fun Connection.migrateTo8() = ProgressJournalSchema.MIGRATION_7_8.forEach { sql(it) }

    @Test
    fun `migrating to version 8 gives exactly the exported version 8 schema`() {
        val migrated = open().apply { create(7) }.apply { migrateTo8() }.apply { guard() }
        val fresh = open().apply { create(8) }.apply { guard() }

        for (pragma in listOf("table_info", "foreign_key_list", "index_list")) {
            migrated.rows("PRAGMA $pragma(offline_progress)") shouldBe fresh.rows("PRAGMA $pragma(offline_progress)")
        }
        migrated.rows("PRAGMA foreign_key_list(offline_progress)") shouldBe emptyList()
        migrated.rows("SELECT name FROM sqlite_master WHERE type = 'trigger'") shouldBe
            fresh.rows("SELECT name FROM sqlite_master WHERE type = 'trigger'")
        migrated.rows("PRAGMA foreign_key_check").shouldBe(emptyList())
    }

    @Test
    fun `migrating to version 8 keeps every journal entry`() {
        val db = open().apply { create(7) }.apply { seed() }
        val before = db.rows("SELECT * FROM offline_progress ORDER BY download_id")

        db.migrateTo8()

        db.rows("SELECT * FROM offline_progress ORDER BY download_id") shouldBe before
    }

    @Test
    fun `removing downloads keeps unsynced progress and purges only settled entries`() {
        val db = seeded()

        for (id in 1L..3L) {
            db.sql("DELETE FROM download WHERE id = $id")
            db.purge(id)
        }

        db.journalIds() shouldBe listOf("1", "2")
    }

    @Test
    fun `removing the whole account keeps unsynced progress`() {
        val db = seeded()

        db.sql("DELETE FROM user WHERE id = 1")
        db.sql("DELETE FROM server WHERE id = 1")

        db.rows("SELECT COUNT(*) FROM download").single().single() shouldBe "0"
        db.journalIds() shouldBe listOf("1", "2", "3")
    }

    @Test
    fun `unsynced progress cannot be deleted, even directly`() {
        val db = seeded()

        for (id in 1..2) {
            shouldThrow<SQLException> { db.sql("DELETE FROM offline_progress WHERE download_id = $id") }
                .message shouldContain "unsynced offline progress"
        }
        db.journalIds() shouldBe listOf("1", "2", "3")
    }

    @Test
    fun `a destructive recreation can still drop the journal table`() {
        val db = seeded()

        db.sql("DROP TABLE offline_progress")

        db.rows("SELECT name FROM sqlite_master WHERE name LIKE 'offline_progress%'") shouldBe emptyList()
    }

    @Test
    fun `rewriting an unsynced entry is still allowed`() {
        val db = seeded()

        db.journal(1, dirty = false, operationId = "'op-1'", state = "PENDING", verb = "INSERT OR REPLACE")

        db.rows("SELECT op_id FROM offline_progress WHERE download_id = 1").single().single() shouldBe "op-1"
    }

    @Test
    fun `an acknowledged or conflicted entry of a removed download is purged, a rejected one is kept`() {
        val db = seeded()
        for (id in 1L..3L) db.sql("DELETE FROM download WHERE id = $id")

        db.purge(1)
        db.purge(2)
        db.journalIds() shouldBe listOf("1", "2", "3")

        db.journal(1, dirty = false, operationId = null, state = "CLEAN", verb = "INSERT OR REPLACE")
        db.journal(2, dirty = false, operationId = null, state = "CONFLICTED", verb = "INSERT OR REPLACE")
        db.journal(3, dirty = false, operationId = null, state = "REJECTED", verb = "INSERT OR REPLACE")
        (1L..3L).forEach { db.purge(it) }

        db.journalIds() shouldBe listOf("3")
    }

    @Test
    fun `an entry whose download still exists is never purged`() {
        val db = seeded()

        db.purge(3)

        db.journalIds() shouldBe listOf("1", "2", "3")
    }
}
