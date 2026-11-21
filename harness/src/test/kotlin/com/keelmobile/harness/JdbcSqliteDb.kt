package com.keelmobile.harness

import java.sql.Connection
import java.sql.DriverManager

class JdbcSqliteDb(private val conn: Connection) : SqliteDb {
    override fun exec(sql: String, args: List<Any?>) {
        conn.prepareStatement(sql).use { ps ->
            args.forEachIndexed { idx, value ->
                when (value) {
                    null -> ps.setObject(idx + 1, null)
                    is ByteArray -> ps.setBytes(idx + 1, value)
                    is Int -> ps.setInt(idx + 1, value)
                    is Long -> ps.setLong(idx + 1, value)
                    is Double -> ps.setDouble(idx + 1, value)
                    else -> ps.setString(idx + 1, value.toString())
                }
            }
            ps.execute()
        }
    }

    override fun query(sql: String, args: List<Any?>): SqliteResultSet {
        conn.prepareStatement(sql).use { ps ->
            args.forEachIndexed { idx, value ->
                when (value) {
                    null -> ps.setObject(idx + 1, null)
                    is ByteArray -> ps.setBytes(idx + 1, value)
                    is Int -> ps.setInt(idx + 1, value)
                    is Long -> ps.setLong(idx + 1, value)
                    is Double -> ps.setDouble(idx + 1, value)
                    else -> ps.setString(idx + 1, value.toString())
                }
            }
            ps.executeQuery().use { rs ->
                val meta = rs.metaData
                val cols = (1..meta.columnCount).map { meta.getColumnLabel(it) }
                val rows = mutableListOf<List<Any?>>()
                while (rs.next()) {
                    rows += (1..meta.columnCount).map { rs.getObject(it) }
                }
                return SqliteResultSet(cols, rows)
            }
        }
    }

    override fun close() {
        conn.close()
    }

    companion object {
        fun openInMemory(): JdbcSqliteDb =
            JdbcSqliteDb(DriverManager.getConnection("jdbc:sqlite::memory:"))
    }
}
