package com.keelmobile.harness

import kotlinx.serialization.Serializable
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlin.math.min

/**
 * Git blob SHA-1 verification ("blob <size>\0<bytes>").
 */
object GitBlob {
    fun sha1Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update("blob ${bytes.size}\u0000".toByteArray(Charsets.US_ASCII))
        md.update(bytes)
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun verify(bytes: ByteArray, expectedHex: String): Boolean =
        sha1Hex(bytes).equals(expectedHex, ignoreCase = true)
}

@Serializable
enum class EditOperation {
    REPLACE,
    CREATE,
    DELETE,
    RENAME
}

@Serializable
data class ProposedEdit(
    val id: String,
    val operation: EditOperation,
    val path: String,
    val destinationPath: String? = null,
    val oldText: String? = null,
    val newText: String? = null,
    val baseSha1: String? = null,
    val diffPreview: String = "",
    val status: Status = Status.PENDING
) {
    @Serializable
    enum class Status { PENDING, APPLIED, DISCARDED }
}

data class FileEntry(
    val path: String,
    val bytes: ByteArray,
    val isBinary: Boolean
) {
    val text: String?
        get() = if (isBinary) null else bytes.toString(Charsets.UTF_8)
}

data class SearchMatch(
    val path: String,
    val line: Int,
    val text: String
)

data class SearchResult(
    val mode: String,
    val matches: List<SearchMatch>,
    val totalMatches: Int,
    val skippedBinaryFiles: Int
)

data class ReadFileResult(
    val path: String,
    val startLine: Int,
    val endLine: Int,
    val totalLines: Int,
    val nextStartLine: Int?,
    val content: String
)

/**
 * Virtual disk for one task, backed by SQLite.
 * Paths are exact database keys (never filesystem paths): no traversal, no symlinks,
 * and case-sensitive names stay distinct.
 */
class TaskWorkspace(private val db: SqliteDb) : AutoCloseable {
    init {
        db.exec(
            """
            CREATE TABLE IF NOT EXISTS base_files (
                path TEXT PRIMARY KEY,
                blob_sha TEXT,
                content BLOB NOT NULL,
                is_binary INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.exec(
            """
            CREATE TABLE IF NOT EXISTS overlay_files (
                path TEXT PRIMARY KEY,
                deleted INTEGER NOT NULL DEFAULT 0,
                content BLOB,
                is_binary INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
    }

    fun putBaseFile(path: String, bytes: ByteArray, expectedBlobSha: String? = null) {
        val clean = normalizePath(path) ?: throw IllegalArgumentException("Invalid workspace path: $path")
        if (expectedBlobSha != null && !GitBlob.verify(bytes, expectedBlobSha)) {
            throw IllegalArgumentException("Git blob SHA mismatch for $clean")
        }
        val sha = expectedBlobSha ?: GitBlob.sha1Hex(bytes)
        val binary = if (looksBinary(bytes)) 1 else 0
        db.exec(
            "INSERT OR REPLACE INTO base_files(path, blob_sha, content, is_binary) VALUES (?, ?, ?, ?)",
            listOf(clean, sha, bytes, binary)
        )
    }

    fun putTextFile(path: String, text: String) {
        val clean = normalizePath(path) ?: throw IllegalArgumentException("Invalid workspace path: $path")
        val bytes = text.toByteArray(Charsets.UTF_8)
        db.exec(
            "INSERT OR REPLACE INTO overlay_files(path, deleted, content, is_binary) VALUES (?, 0, ?, 0)",
            listOf(clean, bytes)
        )
    }

    fun deleteFile(path: String) {
        val clean = normalizePath(path) ?: throw IllegalArgumentException("Invalid workspace path: $path")
        db.exec(
            "INSERT OR REPLACE INTO overlay_files(path, deleted, content, is_binary) VALUES (?, 1, NULL, 0)",
            listOf(clean)
        )
    }

    /**
     * Unpacks a GitHub-style gzip tar archive strictly in memory (zero disk extraction),
     * stripping the leading single root folder prefix if present.
     */
    fun importGzipTar(
        gzipBytes: ByteArray,
        expectedTreeShas: Map<String, String>? = null,
        maxFiles: Int = 5_000,
        maxTotalBytes: Long = 64L * 1024L * 1024L
    ): Int {
        var imported = 0
        var totalBytes = 0L
        GZIPInputStream(ByteArrayInputStream(gzipBytes)).use { gis ->
            val header = ByteArray(512)
            while (true) {
                val read = readFully(gis, header)
                if (read < 512 || header.all { it == 0.toByte() }) break
                val rawName = readTarString(header, 0, 100)
                val prefix = readTarString(header, 345, 155)
                val fullName = if (prefix.isNotEmpty()) "$prefix/$rawName" else rawName
                val sizeStr = readTarString(header, 124, 12).trim()
                val size = if (sizeStr.isEmpty()) 0L else sizeStr.toLong(8)
                val typeFlag = header[156].toInt().toChar()

                val padded = ((size + 511L) / 512L) * 512L
                if (typeFlag == '0' || typeFlag == '\u0000') {
                    if (size > maxTotalBytes || totalBytes + size > maxTotalBytes) {
                        throw IllegalArgumentException("Archive exceeds size budget")
                    }
                    if (imported >= maxFiles) {
                        throw IllegalArgumentException("Archive exceeds file count budget")
                    }
                    val content = ByteArray(size.toInt())
                    if (size > 0 && readFully(gis, content) < size) {
                        throw IllegalArgumentException("Truncated tar entry: $fullName")
                    }
                    skipFully(gis, padded - size)

                    val stripped = stripLeadingTarRoot(fullName)
                    val clean = normalizePath(stripped)
                    if (clean != null) {
                        val expectedSha = expectedTreeShas?.get(clean)
                        if (expectedTreeShas == null || expectedSha != null) {
                            putBaseFile(clean, content, expectedSha)
                            imported++
                            totalBytes += size
                        }
                    }
                } else {
                    skipFully(gis, padded)
                }
            }
        }
        return imported
    }

    fun listAllPaths(): List<String> {
        val base = db.query("SELECT path FROM base_files").rows.mapNotNull { it.firstOrNull()?.toString() }.toMutableSet()
        val overlay = db.query("SELECT path, deleted FROM overlay_files").rows
        for (row in overlay) {
            val path = row[0]?.toString() ?: continue
            val deleted = (row[1] as? Number)?.toInt() == 1
            if (deleted) base.remove(path) else base.add(path)
        }
        return base.sorted()
    }

    fun readEntry(path: String): FileEntry? {
        val clean = normalizePath(path) ?: return null
        val ov = db.query("SELECT deleted, content, is_binary FROM overlay_files WHERE path = ?", listOf(clean)).rows.firstOrNull()
        if (ov != null) {
            val deleted = (ov[0] as? Number)?.toInt() == 1
            if (deleted) return null
            val bytes = ov[1] as? ByteArray ?: ByteArray(0)
            val bin = (ov[2] as? Number)?.toInt() == 1
            return FileEntry(clean, bytes, bin)
        }
        val base = db.query("SELECT content, is_binary FROM base_files WHERE path = ?", listOf(clean)).rows.firstOrNull()
            ?: return null
        val bytes = base[0] as? ByteArray ?: ByteArray(0)
        val bin = (base[1] as? Number)?.toInt() == 1
        return FileEntry(clean, bytes, bin)
    }

    fun readText(path: String): String? = readEntry(path)?.text

    fun listFolder(path: String, offset: Int = 0, count: Int = 60): List<String> {
        val norm = if (path.trim() in setOf("", ".", "/")) "" else (normalizePath(path) ?: return emptyList())
        val prefix = if (norm.isEmpty()) "" else "$norm/"
        val entries = linkedSetOf<String>()
        for (file in listAllPaths()) {
            if (!file.startsWith(prefix)) continue
            val rel = file.removePrefix(prefix)
            val slash = rel.indexOf('/')
            if (slash < 0) entries.add(rel) else entries.add(rel.substring(0, slash + 1))
        }
        val sorted = entries.sorted()
        val from = offset.coerceIn(0, sorted.size)
        val to = min(sorted.size, from + count.coerceIn(1, 60))
        return sorted.subList(from, to)
    }

    fun readFileLines(path: String, startLine: Int = 1, count: Int = 200, maxChars: Int = 16_000): ReadFileResult? {
        val entry = readEntry(path) ?: return null
        val text = entry.text ?: throw IllegalArgumentException("File is binary: $path")
        val lines = text.replace("\r\n", "\n").split("\n")
        val total = lines.size
        val start = startLine.coerceAtLeast(1)
        if (start > total) {
            return ReadFileResult(entry.path, start, start - 1, total, null, "")
        }
        val maxCount = count.coerceIn(1, 400)
        val sb = StringBuilder()
        var end = start - 1
        for (lineNum in start..min(total, start + maxCount - 1)) {
            val formatted = "$lineNum: ${lines[lineNum - 1]}\n"
            if (sb.isNotEmpty() && sb.length + formatted.length > maxChars) break
            sb.append(formatted)
            end = lineNum
        }
        val next = if (end < total) end + 1 else null
        return ReadFileResult(
            path = entry.path,
            startLine = start,
            endLine = end,
            totalLines = total,
            nextStartLine = next,
            content = sb.toString().trimEnd('\n')
        )
    }

    /**
     * Multi-mode search: exact substring -> ASCII case-insensitive -> ranked camelCase/word match.
     */
    fun search(query: String, pathFilter: String? = null, count: Int = 20, offset: Int = 0): SearchResult {
        val q = query.trim()
        if (q.isEmpty()) return SearchResult("empty", emptyList(), 0, 0)
        val limit = count.coerceIn(1, 40)
        val skip = offset.coerceAtLeast(0)
        val normFilter = pathFilter?.trim()?.takeIf { it.isNotEmpty() && it != "." && it != "/" }
            ?.let { normalizePath(it) }

        val paths = listAllPaths().filter { p ->
            normFilter == null || p == normFilter || p.startsWith("$normFilter/")
        }

        val textFiles = mutableListOf<Pair<String, List<String>>>()
        var skippedBinary = 0
        for (p in paths) {
            val entry = readEntry(p) ?: continue
            val t = entry.text
            if (t == null) skippedBinary++
            else textFiles += p to t.replace("\r\n", "\n").split("\n")
        }

        // Mode 1: exact bytes/substring
        val exact = mutableListOf<SearchMatch>()
        for ((p, lines) in textFiles) {
            lines.forEachIndexed { idx, line ->
                if (line.contains(q)) exact += SearchMatch(p, idx + 1, line.take(240))
            }
        }
        if (exact.isNotEmpty()) {
            return SearchResult("exact", exact.drop(skip).take(limit), exact.size, skippedBinary)
        }

        // Mode 2: ASCII case-insensitive
        val lowerQ = q.lowercase()
        val ci = mutableListOf<SearchMatch>()
        for ((p, lines) in textFiles) {
            lines.forEachIndexed { idx, line ->
                if (line.lowercase().contains(lowerQ)) ci += SearchMatch(p, idx + 1, line.take(240))
            }
        }
        if (ci.isNotEmpty()) {
            return SearchResult("case-insensitive", ci.drop(skip).take(limit), ci.size, skippedBinary)
        }

        // Mode 3: Ranked word + camelCase token search
        val queryWords = splitSearchTokens(q)
        if (queryWords.isEmpty()) return SearchResult("words", emptyList(), 0, skippedBinary)

        val scored = mutableListOf<Pair<Int, SearchMatch>>()
        for ((p, lines) in textFiles) {
            lines.forEachIndexed { idx, line ->
                val tokens = splitSearchTokens("$p $line")
                val score = queryWords.count { w -> tokens.any { it.contains(w) } }
                if (score > 0) scored += score to SearchMatch(p, idx + 1, line.take(240))
            }
        }
        val ranked = scored.sortedWith(
            compareByDescending<Pair<Int, SearchMatch>> { it.first }
                .thenBy { it.second.path }
                .thenBy { it.second.line }
        ).map { it.second }

        return SearchResult("words", ranked.drop(skip).take(limit), ranked.size, skippedBinary)
    }

    /**
     * Byte-stable repository map for the system prompt so KV prefix caching stays hot.
     */
    fun repoMap(charBudget: Int = 2_400): String {
        val paths = listAllPaths()
        if (paths.isEmpty()) return "Workspace files: (empty)"
        val sb = StringBuilder("Workspace map (${paths.size} files):\n")
        for (p in paths) {
            val entry = readEntry(p) ?: continue
            val decls = entry.text?.let(::extractTopDeclarations).orEmpty()
            val line = if (decls.isEmpty()) "- $p\n" else "- $p (${decls.joinToString(", ")})\n"
            if (sb.length + line.length > charBudget) {
                sb.append("... (truncated to budget)\n")
                break
            }
            sb.append(line)
        }
        return sb.toString().trimEnd()
    }

    /**
     * Plans an edit proposal WITHOUT mutating the workspace.
     * Applying requires an explicit user UI decision via [applyProposal].
     */
    fun planEdit(
        id: String,
        operation: EditOperation,
        path: String,
        oldText: String? = null,
        newText: String? = null,
        destinationPath: String? = null
    ): ProposedEdit {
        val clean = normalizePath(path) ?: throw IllegalArgumentException("Invalid path: $path")
        return when (operation) {
            EditOperation.REPLACE -> {
                val entry = readEntry(clean) ?: throw IllegalArgumentException("File does not exist: $clean")
                val current = entry.text ?: throw IllegalArgumentException("Cannot edit binary file: $clean")
                val target = oldText ?: throw IllegalArgumentException("old_text is required")
                val replacement = newText ?: throw IllegalArgumentException("new_text is required")
                val occurrences = ToolCallText.occurrences(target, current)
                if (occurrences == 0) throw IllegalArgumentException("old_text not found in $clean")
                if (occurrences > 1) throw IllegalArgumentException("old_text matched $occurrences times in $clean; include more context so it matches once")
                val updated = current.replaceFirst(target, replacement)
                ProposedEdit(
                    id = id,
                    operation = EditOperation.REPLACE,
                    path = clean,
                    oldText = target,
                    newText = replacement,
                    baseSha1 = GitBlob.sha1Hex(entry.bytes),
                    diffPreview = unifiedDiff(clean, current, updated)
                )
            }
            EditOperation.CREATE -> {
                if (readEntry(clean) != null) throw IllegalArgumentException("File already exists: $clean")
                val content = newText ?: ""
                ProposedEdit(
                    id = id,
                    operation = EditOperation.CREATE,
                    path = clean,
                    newText = content,
                    diffPreview = unifiedDiff(clean, "", content)
                )
            }
            EditOperation.DELETE -> {
                val entry = readEntry(clean) ?: throw IllegalArgumentException("File does not exist: $clean")
                val current = entry.text ?: "(binary file)"
                ProposedEdit(
                    id = id,
                    operation = EditOperation.DELETE,
                    path = clean,
                    baseSha1 = GitBlob.sha1Hex(entry.bytes),
                    diffPreview = unifiedDiff(clean, current, "")
                )
            }
            EditOperation.RENAME -> {
                val dest = destinationPath?.let(::normalizePath)
                    ?: throw IllegalArgumentException("Invalid destination_path")
                val entry = readEntry(clean) ?: throw IllegalArgumentException("File does not exist: $clean")
                if (readEntry(dest) != null) throw IllegalArgumentException("Destination already exists: $dest")
                ProposedEdit(
                    id = id,
                    operation = EditOperation.RENAME,
                    path = clean,
                    destinationPath = dest,
                    baseSha1 = GitBlob.sha1Hex(entry.bytes),
                    diffPreview = "--- a/$clean\n+++ b/$dest\n(rename; content unchanged)"
                )
            }
        }
    }

    /**
     * Applies a previously planned proposal after explicit user approval,
     * rejecting stale bases if the file changed in the meantime.
     */
    fun applyProposal(proposal: ProposedEdit) {
        when (proposal.operation) {
            EditOperation.REPLACE -> {
                val entry = readEntry(proposal.path) ?: throw IllegalStateException("File disappeared: ${proposal.path}")
                if (proposal.baseSha1 != null && !GitBlob.verify(entry.bytes, proposal.baseSha1)) {
                    throw IllegalStateException("Stale base: ${proposal.path} changed since this proposal was created")
                }
                val current = entry.text ?: throw IllegalStateException("Binary file cannot be edited")
                val target = proposal.oldText ?: throw IllegalStateException("Missing old_text")
                val replacement = proposal.newText ?: throw IllegalStateException("Missing new_text")
                if (ToolCallText.occurrences(target, current) != 1) {
                    throw IllegalStateException("Stale base: old_text no longer matches uniquely")
                }
                putTextFile(proposal.path, current.replaceFirst(target, replacement))
            }
            EditOperation.CREATE -> {
                if (readEntry(proposal.path) != null) {
                    throw IllegalStateException("File already exists: ${proposal.path}")
                }
                putTextFile(proposal.path, proposal.newText ?: "")
            }
            EditOperation.DELETE -> {
                val entry = readEntry(proposal.path) ?: throw IllegalStateException("File does not exist: ${proposal.path}")
                if (proposal.baseSha1 != null && !GitBlob.verify(entry.bytes, proposal.baseSha1)) {
                    throw IllegalStateException("Stale base: ${proposal.path} changed since proposal")
                }
                deleteFile(proposal.path)
            }
            EditOperation.RENAME -> {
                val dest = proposal.destinationPath ?: throw IllegalStateException("Missing destination")
                val entry = readEntry(proposal.path) ?: throw IllegalStateException("File disappeared: ${proposal.path}")
                if (proposal.baseSha1 != null && !GitBlob.verify(entry.bytes, proposal.baseSha1)) {
                    throw IllegalStateException("Stale base: ${proposal.path} changed since proposal")
                }
                if (readEntry(dest) != null) throw IllegalStateException("Destination already exists: $dest")
                db.exec(
                    "INSERT OR REPLACE INTO overlay_files(path, deleted, content, is_binary) VALUES (?, 0, ?, ?)",
                    listOf(dest, entry.bytes, if (entry.isBinary) 1 else 0)
                )
                deleteFile(proposal.path)
            }
        }
    }

    fun diffAgainstBase(): List<WorkspaceChange> {
        val overlayRows = db.query("SELECT path, deleted, content, is_binary FROM overlay_files ORDER BY path").rows
        val basePaths = db.query("SELECT path FROM base_files").rows.mapNotNull { it.firstOrNull()?.toString() }.toSet()
        val changes = mutableListOf<WorkspaceChange>()
        for (row in overlayRows) {
            val path = row[0]?.toString() ?: continue
            val deleted = (row[1] as? Number)?.toInt() == 1
            val bytes = row[2] as? ByteArray
            val isBinary = (row[3] as? Number)?.toInt() == 1
            when {
                deleted && path in basePaths -> changes += WorkspaceChange(path, WorkspaceChange.Kind.DELETED, null)
                !deleted && path in basePaths -> changes += WorkspaceChange(
                    path,
                    WorkspaceChange.Kind.MODIFIED,
                    if (isBinary) null else bytes?.toString(Charsets.UTF_8)
                )
                !deleted -> changes += WorkspaceChange(
                    path,
                    WorkspaceChange.Kind.CREATED,
                    if (isBinary) null else bytes?.toString(Charsets.UTF_8)
                )
            }
        }
        return changes
    }

    override fun close() {
        db.close()
    }

    companion object {
        private val DECL_REGEX = Regex(
            """^\s*(?:public|private|internal|export|async)?\s*(?:class|interface|object|struct|enum|fun|def|fn|function)\s+([A-Za-z_][A-Za-z0-9_]*)"""
        )

        fun normalizePath(raw: String): String? {
            var p = raw.trim().replace('\\', '/')
            while (p.startsWith("./")) p = p.removePrefix("./")
            p = p.trimStart('/')
            if (p.isEmpty() || p.length > 512) return null
            if (p.any { it == '\u0000' || it == '\n' || it == '\r' }) return null
            val parts = p.split('/')
            if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
            return parts.joinToString("/")
        }

        fun looksBinary(bytes: ByteArray): Boolean {
            val check = min(bytes.size, 8192)
            for (i in 0 until check) {
                if (bytes[i] == 0.toByte()) return true
            }
            return false
        }

        private fun splitSearchTokens(text: String): List<String> {
            val splitCamel = text.replace(Regex("([a-z])([A-Z])"), "$1 $2")
            return splitCamel.lowercase()
                .split(Regex("[^a-z0-9_]+"))
                .filter { it.length >= 2 }
                .distinct()
        }

        private fun extractTopDeclarations(text: String): List<String> =
            text.lines().mapNotNull { line -> DECL_REGEX.find(line)?.groupValues?.get(1) }.take(5)

        fun unifiedDiff(path: String, before: String, after: String): String {
            val a = if (before.isEmpty()) emptyList() else before.replace("\r\n", "\n").split("\n")
            val b = if (after.isEmpty()) emptyList() else after.replace("\r\n", "\n").split("\n")
            var prefix = 0
            while (prefix < a.size && prefix < b.size && a[prefix] == b[prefix]) prefix++
            var suffix = 0
            while (suffix < (a.size - prefix) && suffix < (b.size - prefix) &&
                a[a.size - 1 - suffix] == b[b.size - 1 - suffix]
            ) suffix++

            val sb = StringBuilder()
            sb.append("--- a/").append(path).append('\n')
            sb.append("+++ b/").append(path).append('\n')
            val ctxStart = maxOf(0, prefix - 2)
            val aEnd = a.size - suffix
            val bEnd = b.size - suffix
            val ctxEndA = min(a.size, aEnd + 2)
            sb.append("@@ -${ctxStart + 1},${aEnd - ctxStart + (ctxEndA - aEnd)} +${ctxStart + 1},${bEnd - ctxStart + (ctxEndA - aEnd)} @@\n")
            for (i in ctxStart until prefix) sb.append(' ').append(a[i]).append('\n')
            for (i in prefix until aEnd) sb.append('-').append(a[i]).append('\n')
            for (i in prefix until bEnd) sb.append('+').append(b[i]).append('\n')
            for (i in aEnd until ctxEndA) sb.append(' ').append(a[i]).append('\n')
            return sb.toString().trimEnd('\n')
        }

        private fun stripLeadingTarRoot(path: String): String {
            val trimmed = path.trimStart('/')
            val slash = trimmed.indexOf('/')
            return if (slash >= 0) trimmed.substring(slash + 1) else trimmed
        }

        private fun readTarString(buf: ByteArray, offset: Int, len: Int): String {
            var end = offset
            while (end < offset + len && buf[end] != 0.toByte()) end++
            return String(buf, offset, end - offset, Charsets.UTF_8)
        }

        private fun readFully(stream: GZIPInputStream, dst: ByteArray): Int {
            var total = 0
            while (total < dst.size) {
                val n = stream.read(dst, total, dst.size - total)
                if (n <= 0) break
                total += n
            }
            return total
        }

        private fun skipFully(stream: GZIPInputStream, count: Long) {
            var remaining = count
            val buf = ByteArray(4096)
            while (remaining > 0) {
                val n = stream.read(buf, 0, min(buf.size.toLong(), remaining).toInt())
                if (n <= 0) break
                remaining -= n
            }
        }
    }
}
