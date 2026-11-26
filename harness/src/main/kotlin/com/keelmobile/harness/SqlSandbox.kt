package com.keelmobile.harness

import kotlin.math.min

/**
 * Locked-down scratch SQLite sandbox for `run_sql`.
 * - Persists scratch tables per task (up to 4 MB cap)
 * - Auto-mounts every `.csv` file in the chat workspace as a queryable table
 *   (both `"sales.csv"` and `sales`)
 * - Exposes a read-only `files` view listing every file in the chat
 *   (path, folder, name, extension, bytes, lines, kind)
 * - Blocks ATTACH, DETACH, load_extension, and unsafe PRAGMAs.
 */
class SqlSandbox(
    private val db: SqliteDb,
    private val maxRows: Int = 200,
    private val maxOutputChars: Int = 12_000
) : AutoCloseable {

    data class ExecutionResult(
        val output: String,
        val rowCount: Int,
        val truncated: Boolean
    )

    private val mountedCsvTables = mutableSetOf<String>()

    fun refreshWorkspaceViews(workspace: TaskWorkspace) {
        // Drop previously mounted CSV tables and rebuild from current workspace state
        for (table in mountedCsvTables) {
            runCatching { db.exec("""DROP TABLE IF EXISTS "${escapeIdent(table)}"""") }
        }
        mountedCsvTables.clear()

        db.exec("DROP VIEW IF EXISTS files")
        db.exec("DROP TABLE IF EXISTS _keel_files_meta")
        db.exec(
            """
            CREATE TABLE _keel_files_meta (
                path TEXT NOT NULL,
                folder TEXT NOT NULL,
                name TEXT NOT NULL,
                extension TEXT NOT NULL,
                bytes INTEGER NOT NULL,
                lines INTEGER NOT NULL,
                kind TEXT NOT NULL
            )
            """.trimIndent()
        )

        val shortNamesTaken = mutableSetOf<String>()
        for (path in workspace.listAllPaths()) {
            val entry = workspace.readEntry(path) ?: continue
            val folder = if ('/' in path) path.substringBeforeLast('/') else "."
            val name = path.substringAfterLast('/')
            val ext = if ('.' in name) name.substringAfterLast('.').lowercase() else ""
            val lineCount = entry.text?.let { if (it.isEmpty()) 0 else it.count { c -> c == '\n' } + 1 } ?: 0
            val kind = when {
                entry.isBinary -> "binary"
                ext == "csv" -> "csv"
                ext == "md" -> "markdown"
                ext in setOf("html", "htm") -> "web"
                ext in setOf("kt", "swift", "py", "js", "ts", "rs", "java", "c", "cpp", "h") -> "code"
                else -> "text"
            }
            db.exec(
                "INSERT INTO _keel_files_meta VALUES (?, ?, ?, ?, ?, ?, ?)",
                listOf(path, folder, name, ext, entry.bytes.size, lineCount, kind)
            )

            if (ext == "csv" && entry.text != null) {
                val parsed = parseCsv(entry.text!!)
                if (parsed.headers.isNotEmpty()) {
                    mountCsvTable(name, parsed)
                    val stem = name.removeSuffix(".csv").removeSuffix(".CSV")
                    if (stem.isNotEmpty() && stem != name && shortNamesTaken.add(stem.lowercase())) {
                        mountCsvTable(stem, parsed)
                    }
                }
            }
        }
        db.exec("CREATE VIEW files AS SELECT path, folder, name, extension, bytes, lines, kind FROM _keel_files_meta")
    }

    private fun mountCsvTable(tableName: String, csv: ParsedCsv) {
        val safeTable = escapeIdent(tableName)
        val colDefs = csv.headers.mapIndexed { idx, col ->
            val colName = escapeIdent(col.ifBlank { "col_${idx + 1}" })
            val affinity = if (csv.isNumericColumn(idx)) "REAL" else "TEXT"
            """"$colName" $affinity"""
        }.joinToString(", ")
        db.exec("""DROP TABLE IF EXISTS "$safeTable"""")
        db.exec("""CREATE TABLE "$safeTable" ($colDefs)""")
        mountedCsvTables += tableName

        val placeholders = csv.headers.indices.joinToString(", ") { "?" }
        val insertSql = """INSERT INTO "$safeTable" VALUES ($placeholders)"""
        for (row in csv.rows) {
            val values = csv.headers.indices.map { idx ->
                val raw = row.getOrNull(idx).orEmpty()
                if (csv.isNumericColumn(idx)) raw.toDoubleOrNull() else raw
            }
            db.exec(insertSql, values)
        }
    }

    fun execute(rawSql: String): ExecutionResult {
        val sql = rewriteBareCsvIdentifiers(rawSql.trim())
        if (sql.isEmpty()) throw IllegalArgumentException("SQL statement is empty.")
        validateSql(sql)

        val statements = splitStatements(sql)
        if (statements.isEmpty()) throw IllegalArgumentException("SQL statement is empty.")

        val out = StringBuilder()
        var totalRows = 0
        var truncated = false

        for (stmt in statements) {
            val trimmed = stmt.trim()
            if (trimmed.isEmpty()) continue
            val upper = trimmed.uppercase()
            val isQuery = upper.startsWith("SELECT") || upper.startsWith("WITH") || upper.startsWith("EXPLAIN")
            if (isQuery) {
                val rs = db.query(trimmed)
                if (rs.columns.isNotEmpty()) {
                    out.append(rs.columns.joinToString(" | ")).append('\n')
                    out.append(rs.columns.joinToString("-+-") { "-".repeat(min(12, maxOf(3, it.length))) }).append('\n')
                }
                val limit = min(rs.rows.size, maxRows)
                for (i in 0 until limit) {
                    val line = rs.rows[i].joinToString(" | ") { formatCell(it) }
                    if (out.length + line.length + 1 > maxOutputChars) {
                        truncated = true
                        break
                    }
                    out.append(line).append('\n')
                    totalRows++
                }
                if (rs.rows.size > maxRows) {
                    truncated = true
                    out.append("... (${rs.rows.size - maxRows} more rows truncated)\n")
                }
            } else {
                db.exec(trimmed)
                out.append("OK\n")
            }
        }

        val text = out.toString().trimEnd('\n').ifEmpty { "(no rows)" }
        return ExecutionResult(text, totalRows, truncated)
    }

    override fun close() {
        db.close()
    }

    companion object {
        private val FORBIDDEN_REGEX = Regex(
            """\b(ATTACH|DETACH|LOAD_EXTENSION|PRAGMA\s+(?!table_info\b))\b""",
            RegexOption.IGNORE_CASE
        )

        // Allow `SELECT * FROM sales.csv` by quoting bare `name.csv` identifiers.
        private val BARE_CSV_TABLE = Regex("""(?<![\w"'])([A-Za-z0-9_-]+\.csv)(?![\w"'])""", RegexOption.IGNORE_CASE)

        fun rewriteBareCsvIdentifiers(sql: String): String =
            BARE_CSV_TABLE.replace(sql) { m -> "\"${m.groupValues[1]}\"" }

        fun validateSql(sql: String) {
            val stripped = stripCommentsAndStrings(sql)
            if (FORBIDDEN_REGEX.containsMatchIn(stripped)) {
                throw SecurityException("Blocked SQL operation: ATTACH, DETACH, load_extension, and PRAGMA are not allowed in the scratch sandbox.")
            }
        }

        private fun stripCommentsAndStrings(sql: String): String {
            val sb = StringBuilder()
            var i = 0
            while (i < sql.length) {
                when {
                    sql[i] == '\'' -> {
                        i++
                        while (i < sql.length) {
                            if (sql[i] == '\'' && i + 1 < sql.length && sql[i + 1] == '\'') i += 2
                            else if (sql[i] == '\'') { i++; break }
                            else i++
                        }
                        sb.append(' ')
                    }
                    sql.startsWith("--", i) -> {
                        while (i < sql.length && sql[i] != '\n') i++
                    }
                    sql.startsWith("/*", i) -> {
                        i += 2
                        while (i + 1 < sql.length && !(sql[i] == '*' && sql[i + 1] == '/')) i++
                        i += 2
                    }
                    else -> {
                        sb.append(sql[i])
                        i++
                    }
                }
            }
            return sb.toString()
        }

        private fun splitStatements(sql: String): List<String> {
            val out = mutableListOf<String>()
            val cur = StringBuilder()
            var inSingle = false
            var inDouble = false
            var i = 0
            while (i < sql.length) {
                val c = sql[i]
                when {
                    c == '\'' && !inDouble -> {
                        inSingle = !inSingle
                        cur.append(c)
                    }
                    c == '"' && !inSingle -> {
                        inDouble = !inDouble
                        cur.append(c)
                    }
                    c == ';' && !inSingle && !inDouble -> {
                        val s = cur.toString().trim()
                        if (s.isNotEmpty()) out += s
                        cur.setLength(0)
                    }
                    else -> cur.append(c)
                }
                i++
            }
            val tail = cur.toString().trim()
            if (tail.isNotEmpty()) out += tail
            return out
        }

        private fun escapeIdent(name: String): String = name.replace("\"", "\"\"")

        private fun formatCell(v: Any?): String = when (v) {
            null -> "NULL"
            is Double -> if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
            else -> v.toString()
        }

        data class ParsedCsv(val headers: List<String>, val rows: List<List<String>>) {
            fun isNumericColumn(idx: Int): Boolean {
                var sawNonEmpty = false
                for (r in rows) {
                    val v = r.getOrNull(idx)?.trim().orEmpty()
                    if (v.isEmpty()) continue
                    sawNonEmpty = true
                    if (v.toDoubleOrNull() == null) return false
                }
                return sawNonEmpty
            }
        }

        fun parseCsv(text: String): ParsedCsv {
            val rows = mutableListOf<List<String>>()
            val row = mutableListOf<String>()
            val cell = StringBuilder()
            var inQuotes = false
            val normalized = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace("\r", "\n")
            var i = 0
            while (i < normalized.length) {
                val c = normalized[i]
                when {
                    inQuotes -> {
                        if (c == '"') {
                            if (i + 1 < normalized.length && normalized[i + 1] == '"') {
                                cell.append('"')
                                i++
                            } else {
                                inQuotes = false
                            }
                        } else {
                            cell.append(c)
                        }
                    }
                    c == '"' -> inQuotes = true
                    c == ',' -> {
                        row += cell.toString()
                        cell.setLength(0)
                    }
                    c == '\n' -> {
                        row += cell.toString()
                        cell.setLength(0)
                        if (row.any { it.isNotEmpty() }) rows += row.toList()
                        row.clear()
                    }
                    else -> cell.append(c)
                }
                i++
            }
            if (cell.isNotEmpty() || row.isNotEmpty()) {
                row += cell.toString()
                if (row.any { it.isNotEmpty() }) rows += row.toList()
            }
            if (rows.isEmpty()) return ParsedCsv(emptyList(), emptyList())
            val rawHeaders = rows.first()
            val seen = mutableMapOf<String, Int>()
            val headers = rawHeaders.mapIndexed { idx, h ->
                val base = h.trim().ifEmpty { "col_${idx + 1}" }
                val count = seen.getOrDefault(base.lowercase(), 0)
                seen[base.lowercase()] = count + 1
                if (count == 0) base else "${base}_${count + 1}"
            }
            return ParsedCsv(headers, rows.drop(1))
        }
    }
}
