package com.keelmobile.android.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.keelmobile.harness.SqliteDb
import com.keelmobile.harness.SqliteResultSet
import java.io.File

/**
 * Android SQLite implementation of :harness's SqliteDb interface.
 * Works both in-memory (scratch SQL sandbox) and on-disk (persistent task workspaces).
 */
class AndroidSqliteDb(private val db: SQLiteDatabase) : SqliteDb {

    override fun exec(sql: String, args: List<Any?>) {
        if (args.isEmpty()) {
            db.execSQL(sql)
        } else {
            val stmt = db.compileStatement(sql)
            try {
                args.forEachIndexed { idx, value ->
                    val pos = idx + 1
                    when (value) {
                        null -> stmt.bindNull(pos)
                        is ByteArray -> stmt.bindBlob(pos, value)
                        is Int -> stmt.bindLong(pos, value.toLong())
                        is Long -> stmt.bindLong(pos, value)
                        is Float -> stmt.bindDouble(pos, value.toDouble())
                        is Double -> stmt.bindDouble(pos, value)
                        else -> stmt.bindString(pos, value.toString())
                    }
                }
                stmt.execute()
            } finally {
                stmt.close()
            }
        }
    }

    override fun query(sql: String, args: List<Any?>): SqliteResultSet {
        val strArgs = if (args.isEmpty()) null else args.map { it?.toString() ?: "" }.toTypedArray()
        val cursor = db.rawQuery(sql, strArgs)
        cursor.use { c ->
            val cols = c.columnNames.toList()
            val rows = mutableListOf<List<Any?>>()
            while (c.moveToNext()) {
                val row = ArrayList<Any?>(cols.size)
                for (i in cols.indices) {
                    val v: Any? = when (c.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> null
                        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                        Cursor.FIELD_TYPE_BLOB -> c.getBlob(i)
                        else -> c.getString(i)
                    }
                    row.add(v)
                }
                rows.add(row)
            }
            return SqliteResultSet(cols, rows)
        }
    }

    override fun close() {
        if (db.isOpen) db.close()
    }

    companion object {
        fun openInMemory(): AndroidSqliteDb =
            AndroidSqliteDb(SQLiteDatabase.create(null))

        fun openFile(file: File): AndroidSqliteDb {
            file.parentFile?.mkdirs()
            return AndroidSqliteDb(SQLiteDatabase.openOrCreateDatabase(file, null))
        }
    }
}
