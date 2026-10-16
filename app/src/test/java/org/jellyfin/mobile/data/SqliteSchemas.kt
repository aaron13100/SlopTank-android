package org.jellyfin.mobile.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/** Real in-memory SQLite for the database rules, built from the schemas Room exports to app/schemas. */
fun openSqlite(): Connection = DriverManager.getConnection("jdbc:sqlite::memory:").also { db ->
    db.sql("PRAGMA foreign_keys = ON")
}

private fun schema(version: Int): JsonObject {
    val file = File("schemas/org.jellyfin.mobile.data.JellyfinDatabase/$version.json")
    return Json.parseToJsonElement(file.readText()).jsonObject.getValue("database").jsonObject
}

/** Creates the tables and indices of an exported schema, as Room does on a fresh install. */
fun Connection.create(version: Int) {
    for (entity in schema(version).getValue("entities").jsonArray.map { it.jsonObject }) {
        val table = entity.getValue("tableName").jsonPrimitive.content
        val statements = listOf(entity.getValue("createSql")) +
            (entity["indices"] as JsonArray? ?: JsonArray(emptyList())).map { it.jsonObject.getValue("createSql") }
        statements.forEach { sql(it.jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
    }
}

fun Connection.sql(sql: String) = createStatement().use { it.execute(sql) }

fun Connection.rows(sql: String): List<List<String?>> = createStatement().use { statement ->
    statement.executeQuery(sql).use { result ->
        val columns = result.metaData.columnCount
        buildList { while (result.next()) add((1..columns).map { result.getString(it) }) }
    }
}

/** Binds Room's named parameters (`:name`) as SQL literals; strings are quoted, null becomes NULL. */
fun String.bind(vararg parameters: Pair<String, Any?>): String = parameters.fold(this) { sql, (name, value) ->
    val literal = when (value) {
        null -> "NULL"
        is Number -> value.toString()
        else -> "'" + value.toString().replace("'", "''") + "'"
    }
    sql.replace(Regex(":$name\\b"), Regex.escapeReplacement(literal))
}
