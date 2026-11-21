package com.keelmobile.harness

/**
 * Cross-platform SQLite abstraction so :harness runs identically on JVM (sqlite-jdbc)
 * and on Android (android.database.sqlite.SQLiteDatabase).
 */
interface SqliteDb : AutoCloseable {
    fun exec(sql: String, args: List<Any?> = emptyList())
    fun query(sql: String, args: List<Any?> = emptyList()): SqliteResultSet
    fun queryLong(sql: String, args: List<Any?> = emptyList()): Long? {
        val rs = query(sql, args)
        val first = rs.rows.firstOrNull()?.firstOrNull() ?: return null
        return when (first) {
            is Number -> first.toLong()
            is String -> first.toLongOrNull()
            else -> null
        }
    }
}

data class SqliteResultSet(
    val columns: List<String>,
    val rows: List<List<Any?>>
)

fun interface SqliteDbFactory {
    fun openInMemory(): SqliteDb
}
