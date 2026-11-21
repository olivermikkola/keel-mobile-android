package com.keelmobile.android.data

import com.keelmobile.android.inference.LlamaNative
import com.keelmobile.harness.ModelCatalog
import com.keelmobile.harness.Recall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
private data class StoredVectorEntry(
    val key: String,
    val source: String,
    val text: String,
    val vector: List<Float>
)

/**
 * On-device semantic recall backed by EmbeddingGemma 300M via LlamaNative.nativeEmbed.
 * Loads the compact embedding model on CPU, indexes up to 120 new passages per call,
 * persists vectors privately on the phone, ranks via cosine similarity, and unloads.
 */
class RecallStore(
    private val storeDir: File,
    private val modelLibrary: ModelLibraryManager,
    private val taskStore: TaskStore
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val indexFile = File(storeDir, "recall-vectors.json")

    suspend fun searchByMeaning(query: String, currentTaskId: String): String = withContext(Dispatchers.IO) {
        val searchModel = ModelCatalog.search
        val modelFile = modelLibrary.modelFilePath(searchModel)
        if (!modelFile.exists() || !LlamaNative.isAvailable) {
            return@withContext "Search by meaning is not installed. Download 'Search by meaning' in Models first."
        }

        val existing = loadIndex().associateBy { it.key }.toMutableMap()
        val tasks = taskStore.loadTasks().filter { it.id != currentTaskId }

        val candidates = mutableListOf<Triple<String, String, String>>() // key, source, text
        for (task in tasks) {
            val chatText = task.messages.filter { it.role == "user" || it.role == "assistant" }
                .joinToString("\n") { "${it.role}: ${it.text}" }
            Recall.chunkLines(chatText).forEachIndexed { idx, chunk ->
                val key = "chat:${task.id}:$idx:${chunk.hashCode()}"
                candidates += Triple(key, "Chat '${task.title}'", chunk)
            }
            if (task.appliedFilePaths.isNotEmpty()) {
                runCatching {
                    taskStore.openWorkspace(task.id).use { ws ->
                        for (path in task.appliedFilePaths) {
                            val text = ws.readText(path) ?: continue
                            Recall.chunkLines(text).forEachIndexed { idx, chunk ->
                                val key = "file:${task.id}:$path:$idx:${chunk.hashCode()}"
                                candidates += Triple(key, "File '$path' (${task.title})", chunk)
                            }
                        }
                    }
                }
            }
        }

        val handle = LlamaNative.nativeLoadEmbeddingModel(modelFile.absolutePath, 4)
        if (handle == 0L) {
            return@withContext "Could not load the Search by meaning embedding model."
        }
        try {
            var newlyIndexed = 0
            var remainingUnindexed = 0
            for ((key, source, text) in candidates) {
                if (key in existing) continue
                if (newlyIndexed >= MAX_NEW_PER_SEARCH) {
                    remainingUnindexed++
                    continue
                }
                val vec = LlamaNative.nativeEmbed(handle, text) ?: continue
                existing[key] = StoredVectorEntry(key, source, text, vec.toList())
                newlyIndexed++
            }
            if (newlyIndexed > 0) saveIndex(existing.values.toList())

            val qVec = LlamaNative.nativeEmbed(handle, query)
                ?: return@withContext "Could not embed query."
            val passages = existing.values.map { Recall.Passage(it.source, it.text, it.vector.toFloatArray()) }
            val top = Recall.rank(qVec, passages, Recall.MAX_RESULTS)
            if (top.isEmpty()) {
                return@withContext "No relevant passages found in earlier chats or files."
            }
            buildString {
                append("Found ${top.size} relevant passage(s) from earlier chats/files:\n")
                top.forEachIndexed { idx, (passage, score) ->
                    append("\n${idx + 1}. [${passage.source}] (score ${"%.2f".format(score)})\n")
                    append(passage.text.take(450)).append('\n')
                }
                if (remainingUnindexed > 0) {
                    append("\n($remainingUnindexed older passage(s) will be indexed on the next search.)")
                }
            }.trimEnd()
        } finally {
            LlamaNative.nativeUnloadEmbeddingModel(handle)
        }
    }

    private fun loadIndex(): List<StoredVectorEntry> {
        if (!indexFile.exists()) return emptyList()
        return runCatching { json.decodeFromString<List<StoredVectorEntry>>(indexFile.readText()) }.getOrDefault(emptyList())
    }

    private fun saveIndex(list: List<StoredVectorEntry>) {
        val tmp = File(storeDir, "recall-vectors.json.tmp")
        tmp.writeText(json.encodeToString(list))
        if (!tmp.renameTo(indexFile)) {
            tmp.copyTo(indexFile, overwrite = true)
            tmp.delete()
        }
    }

    companion object {
        private const val MAX_NEW_PER_SEARCH = 120
    }
}
