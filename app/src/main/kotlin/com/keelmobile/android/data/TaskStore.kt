package com.keelmobile.android.data

import com.keelmobile.harness.AppPreset
import com.keelmobile.harness.ChatMode
import com.keelmobile.harness.CustomInstructions
import com.keelmobile.harness.MemoryFact
import com.keelmobile.harness.McpConnector
import com.keelmobile.harness.McpServerConfig
import com.keelmobile.harness.ProposedEdit
import com.keelmobile.harness.TaskWorkspace
import com.keelmobile.harness.UserMemory
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class SavedMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String, // "user", "assistant", "system_notice"
    val text: String,
    val stepLabels: List<String> = emptyList(),
    val timestampMs: Long = System.currentTimeMillis()
)

@Serializable
data class SavedTask(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New chat",
    val mode: ChatMode = ChatMode.WORK,
    val appPreset: AppPreset? = null,
    val repositoryName: String? = null,
    val repositoryBranch: String? = null,
    val repositoryCommitSha: String? = null,
    val localFolderUri: String? = null,
    val localFolderName: String? = null,
    val messages: List<SavedMessage> = emptyList(),
    val pendingProposals: List<ProposedEdit> = emptyList(),
    val appliedFilePaths: List<String> = emptyList(),
    val updatedAtMs: Long = System.currentTimeMillis()
)

@Serializable
data class LibraryItem(
    val taskId: String,
    val taskTitle: String,
    val path: String,
    val kind: String,
    val updatedAtMs: Long
)

@Serializable
data class UserPreferences(
    val customInstructions: String = "",
    val memoryEnabled: Boolean = true,
    val memoryFacts: List<MemoryFact> = emptyList(),
    val enabledConnectorIds: Set<String> = emptySet(),
    val customConnectors: List<McpServerConfig> = emptyList(),
    val useVulkanGpu: Boolean = true
)

/**
 * Private persistent storage for saved tasks (up to 200), per-task SQLite workspaces,
 * Customize preferences, UserMemory facts, and the Library index.
 */
class TaskStore(private val rootDir: File) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val tasksFile = File(rootDir, "tasks.json")
    private val prefsFile = File(rootDir, "preferences.json")
    private val workspacesDir = File(rootDir, "workspaces")

    init {
        rootDir.mkdirs()
        workspacesDir.mkdirs()
    }

    fun loadTasks(): List<SavedTask> {
        if (!tasksFile.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<SavedTask>>(tasksFile.readText())
                .sortedByDescending { it.updatedAtMs }
        }.getOrDefault(emptyList())
    }

    fun saveTasks(tasks: List<SavedTask>) {
        val capped = tasks.sortedByDescending { it.updatedAtMs }.take(MAX_TASKS)
        atomicWrite(tasksFile, json.encodeToString(capped))
    }

    fun workspaceDbFile(taskId: String): File = File(workspacesDir, "$taskId.db")

    fun openWorkspace(taskId: String): TaskWorkspace =
        TaskWorkspace(AndroidSqliteDb.openFile(workspaceDbFile(taskId)))

    fun deleteWorkspace(taskId: String) {
        workspaceDbFile(taskId).delete()
        File(workspacesDir, "$taskId.db-journal").delete()
        File(workspacesDir, "$taskId.db-wal").delete()
        File(workspacesDir, "$taskId.db-shm").delete()
    }

    fun loadPreferences(): UserPreferences {
        if (!prefsFile.exists()) return UserPreferences()
        return runCatching {
            json.decodeFromString<UserPreferences>(prefsFile.readText())
        }.getOrDefault(UserPreferences())
    }

    fun savePreferences(prefs: UserPreferences) {
        val normalized = prefs.copy(
            customInstructions = CustomInstructions.normalize(prefs.customInstructions),
            memoryFacts = prefs.memoryFacts.sortedByDescending { it.createdAtEpochMs }.take(UserMemory.MAX_FACTS)
        )
        atomicWrite(prefsFile, json.encodeToString(normalized))
    }

    fun allConnectors(prefs: UserPreferences): List<McpServerConfig> =
        (McpConnector.builtinCatalog + prefs.customConnectors).map { srv ->
            srv.copy(enabled = srv.id in prefs.enabledConnectorIds)
        }

    fun buildLibraryIndex(tasks: List<SavedTask>): List<LibraryItem> =
        tasks.filter { it.repositoryName == null }
            .flatMap { task ->
                task.appliedFilePaths.map { path ->
                    val ext = path.substringAfterLast('.', "").lowercase()
                    val kind = when (ext) {
                        "html", "htm" -> "Pages"
                        "md" -> "Docs"
                        "csv" -> "Tables"
                        else -> "Files"
                    }
                    LibraryItem(
                        taskId = task.id,
                        taskTitle = task.title,
                        path = path,
                        kind = kind,
                        updatedAtMs = task.updatedAtMs
                    )
                }
            }
            .sortedByDescending { it.updatedAtMs }

    private fun atomicWrite(target: File, content: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    companion object {
        const val MAX_TASKS = 200
    }
}
