package com.keelmobile.harness

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Unattended background scheduled task (backed by Android WorkManager — unlike
 * iOS's tap-to-run notification workaround).
 */
@Serializable
enum class ScheduleInterval(val label: String, val hours: Long) {
    ONE_SHOT("Run once", 0L),
    EVERY_12H("Every 12 hours", 12L),
    DAILY("Daily", 24L),
    WEEKLY("Weekly", 24L * 7L)
}

@Serializable
data class ScheduledTask(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val prompt: String,
    val interval: ScheduleInterval = ScheduleInterval.DAILY,
    val enabled: Boolean = true,
    val linkedTaskId: String? = null,
    val lastRunEpochMs: Long? = null,
    val lastStatus: String = "Scheduled"
) {
    companion object {
        const val MAX_SCHEDULED = 25

        fun validate(title: String, prompt: String): Pair<String, String> {
            val cleanTitle = title.trim().take(80)
            val cleanPrompt = prompt.trim().take(2_000)
            require(cleanPrompt.isNotEmpty()) { "Scheduled prompt cannot be empty." }
            return (cleanTitle.ifEmpty { cleanPrompt.take(40) }) to cleanPrompt
        }
    }
}

/**
 * Safe local Android folder import rules (Storage Access Framework tree sync).
 */
object LocalFolderPolicy {
    const val MAX_FILES = 400
    const val MAX_FILE_BYTES = 512L * 1024L
    const val MAX_TOTAL_BYTES = 12L * 1024L * 1024L

    val IGNORED_DIRS = setOf(
        ".git", ".gradle", ".idea", ".cxx", "build", "node_modules", "target", "__pycache__", ".venv", "dist"
    )

    val TEXT_EXTENSIONS = setOf(
        "txt", "md", "markdown", "csv", "tsv", "json", "yaml", "yml", "toml", "xml",
        "html", "htm", "css", "js", "mjs", "ts", "tsx", "jsx",
        "kt", "kts", "java", "py", "rs", "swift", "c", "h", "cpp", "hpp", "go", "rb", "sh", "sql",
        "properties", "gradle", "env", "gitignore", "dockerfile", "makefile"
    )

    fun shouldSkipDirectory(name: String): Boolean =
        name.isEmpty() || name in IGNORED_DIRS || name.startsWith(".")

    fun isSupportedTextFile(name: String, sizeBytes: Long): Boolean {
        if (sizeBytes < 0 || sizeBytes > MAX_FILE_BYTES) return false
        val lower = name.lowercase()
        if (lower in setOf("makefile", "dockerfile", "readme", "license")) return true
        val ext = lower.substringAfterLast('.', "")
        return ext in TEXT_EXTENSIONS
    }
}

data class WorkspaceChange(
    val path: String,
    val kind: Kind,
    val content: String?
) {
    enum class Kind { CREATED, MODIFIED, DELETED }
}

/**
 * Validates branch name, commit message, and PR title for explicit GitHub publishing.
 */
object GitHubPublishPlanner {
    private val BRANCH_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9._/-]{0,118}[A-Za-z0-9]$")

    data class PublishPlan(
        val repository: String,
        val baseBranch: String,
        val baseCommitSha: String,
        val headBranch: String,
        val commitMessage: String,
        val prTitle: String,
        val changes: List<WorkspaceChange>
    )

    fun validate(
        repository: String,
        baseBranch: String,
        baseCommitSha: String,
        headBranch: String,
        commitMessage: String,
        prTitle: String,
        changes: List<WorkspaceChange>
    ): PublishPlan {
        val cleanHead = headBranch.trim()
        require(changes.isNotEmpty()) { "No applied changes to publish." }
        require(BRANCH_REGEX.matches(cleanHead) && !cleanHead.contains("..") && cleanHead != baseBranch) {
            "Choose a valid new branch name different from $baseBranch (e.g. keel/update-feature)."
        }
        val cleanMsg = commitMessage.trim().take(200)
        require(cleanMsg.isNotEmpty()) { "Commit message is required." }
        val cleanTitle = prTitle.trim().take(160).ifEmpty { cleanMsg }
        return PublishPlan(repository, baseBranch, baseCommitSha, cleanHead, cleanMsg, cleanTitle, changes)
    }
}
