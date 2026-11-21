package com.keelmobile.android.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.keelmobile.harness.EditOperation
import com.keelmobile.harness.LocalFolderPolicy
import com.keelmobile.harness.ProposedEdit
import com.keelmobile.harness.TaskWorkspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android-native local folder bridge via Storage Access Framework (ACTION_OPEN_DOCUMENT_TREE).
 * Lets a user open any real folder on their phone (Documents, Downloads, Obsidian vault,
 * local project) and writes approved edits directly back to real files on storage.
 */
class LocalFolderBridge(private val context: Context) {

    data class ImportSummary(
        val folderName: String,
        val importedFiles: Int,
        val skippedFiles: Int
    )

    suspend fun importFolderIntoWorkspace(treeUri: Uri, workspace: TaskWorkspace): ImportSummary =
        withContext(Dispatchers.IO) {
            val root = DocumentFile.fromTreeUri(context, treeUri)
                ?: throw IllegalArgumentException("Could not open selected folder.")
            val folderName = root.name ?: "Local folder"
            var imported = 0
            var skipped = 0
            var totalBytes = 0L

            fun walk(dir: DocumentFile, prefix: String) {
                for (child in dir.listFiles()) {
                    val name = child.name ?: continue
                    if (child.isDirectory) {
                        if (LocalFolderPolicy.shouldSkipDirectory(name)) continue
                        val nextPrefix = if (prefix.isEmpty()) name else "$prefix/$name"
                        walk(child, nextPrefix)
                    } else if (child.isFile) {
                        if (imported >= LocalFolderPolicy.MAX_FILES) {
                            skipped++
                            continue
                        }
                        val len = child.length()
                        if (!LocalFolderPolicy.isSupportedTextFile(name, len) ||
                            totalBytes + len > LocalFolderPolicy.MAX_TOTAL_BYTES
                        ) {
                            skipped++
                            continue
                        }
                        val relPath = if (prefix.isEmpty()) name else "$prefix/$name"
                        val bytes = runCatching {
                            context.contentResolver.openInputStream(child.uri)?.use { it.readNBytes(LocalFolderPolicy.MAX_FILE_BYTES.toInt()) }
                        }.getOrNull()
                        if (bytes != null && !TaskWorkspace.looksBinary(bytes)) {
                            workspace.putBaseFile(relPath, bytes)
                            imported++
                            totalBytes += bytes.size
                        } else {
                            skipped++
                        }
                    }
                }
            }

            walk(root, "")
            ImportSummary(folderName, imported, skipped)
        }

    /**
     * Writes an explicitly user-approved edit directly back to the real Android folder.
     * Never called by model output — only after the user taps Apply.
     */
    suspend fun writeApprovedChangeToFolder(
        treeUri: Uri,
        edit: ProposedEdit,
        finalContent: String?
    ): Boolean = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext false
        when (edit.operation) {
            EditOperation.CREATE, EditOperation.REPLACE -> {
                val target = resolveOrCreateFile(root, edit.path) ?: return@withContext false
                val text = finalContent ?: edit.newText ?: return@withContext false
                context.contentResolver.openOutputStream(target.uri, "wt")?.use { out ->
                    out.write(text.toByteArray(Charsets.UTF_8))
                } ?: return@withContext false
                true
            }
            EditOperation.DELETE -> {
                val target = findFile(root, edit.path) ?: return@withContext true
                target.delete()
            }
            EditOperation.RENAME -> {
                val destPath = edit.destinationPath ?: return@withContext false
                val src = findFile(root, edit.path) ?: return@withContext false
                val bytes = context.contentResolver.openInputStream(src.uri)?.use { it.readBytes() }
                    ?: return@withContext false
                val dst = resolveOrCreateFile(root, destPath) ?: return@withContext false
                context.contentResolver.openOutputStream(dst.uri, "wt")?.use { it.write(bytes) }
                    ?: return@withContext false
                src.delete()
            }
        }
    }

    private fun findFile(root: DocumentFile, relPath: String): DocumentFile? {
        var cur: DocumentFile = root
        for (part in relPath.split('/')) {
            cur = cur.findFile(part) ?: return null
        }
        return cur
    }

    private fun resolveOrCreateFile(root: DocumentFile, relPath: String): DocumentFile? {
        val parts = relPath.split('/')
        var dir: DocumentFile = root
        for (i in 0 until parts.lastIndex) {
            val seg = parts[i]
            dir = dir.findFile(seg) ?: dir.createDirectory(seg) ?: return null
        }
        val fileName = parts.last()
        return dir.findFile(fileName) ?: dir.createFile(guessMime(fileName), fileName)
    }

    private fun guessMime(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html"
        "csv" -> "text/csv"
        "md" -> "text/markdown"
        "json" -> "application/json"
        "js" -> "text/javascript"
        "css" -> "text/css"
        else -> "text/plain"
    }
}
