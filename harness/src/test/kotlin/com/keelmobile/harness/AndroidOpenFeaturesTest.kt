package com.keelmobile.harness

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidOpenFeaturesTest {

    @Test
    fun scheduledTaskValidationTrimsAndDefaultsTitle() {
        val (title, prompt) = ScheduledTask.validate("   ", "  Summarize my daily log  ")
        assertEquals("Summarize my daily log", title)
        assertEquals("Summarize my daily log", prompt)

        assertFailsWith<IllegalArgumentException> {
            ScheduledTask.validate("Daily brief", "   ")
        }
    }

    @Test
    fun localFolderPolicySkipsBuildDirsAndBinaryFiles() {
        assertTrue(LocalFolderPolicy.shouldSkipDirectory(".git"))
        assertTrue(LocalFolderPolicy.shouldSkipDirectory("node_modules"))
        assertTrue(LocalFolderPolicy.shouldSkipDirectory("build"))
        assertTrue(LocalFolderPolicy.shouldSkipDirectory(".hidden"))
        assertFalse(LocalFolderPolicy.shouldSkipDirectory("src"))

        assertTrue(LocalFolderPolicy.isSupportedTextFile("notes.md", 1200))
        assertTrue(LocalFolderPolicy.isSupportedTextFile("Makefile", 500))
        assertFalse(LocalFolderPolicy.isSupportedTextFile("video.mp4", 1200))
        assertFalse(LocalFolderPolicy.isSupportedTextFile("huge.txt", LocalFolderPolicy.MAX_FILE_BYTES + 1))
    }

    @Test
    fun workspaceDiffAgainstBaseTracksCreatedModifiedAndDeleted() {
        TaskWorkspace(JdbcSqliteDb.openInMemory()).use { ws ->
            ws.putBaseFile("README.md", "# Hello\n".toByteArray())
            ws.putBaseFile("old.txt", "remove me\n".toByteArray())
            ws.putBaseFile("keep.txt", "unchanged\n".toByteArray())

            ws.putTextFile("README.md", "# Updated\n")
            ws.putTextFile("src/new.kt", "fun ok() = true\n")
            ws.deleteFile("old.txt")

            val changes = ws.diffAgainstBase().associateBy { it.path }
            assertEquals(3, changes.size)
            assertEquals(WorkspaceChange.Kind.MODIFIED, changes["README.md"]?.kind)
            assertEquals("# Updated\n", changes["README.md"]?.content)
            assertEquals(WorkspaceChange.Kind.CREATED, changes["src/new.kt"]?.kind)
            assertEquals(WorkspaceChange.Kind.DELETED, changes["old.txt"]?.kind)
            assertNull(changes["old.txt"]?.content)
        }
    }

    @Test
    fun gitHubPublishPlannerValidatesBranchAndChanges() {
        val changes = listOf(WorkspaceChange("README.md", WorkspaceChange.Kind.MODIFIED, "new"))
        val plan = GitHubPublishPlanner.validate(
            repository = "owner/repo",
            baseBranch = "main",
            baseCommitSha = "abc123",
            headBranch = "keel/update-docs",
            commitMessage = "Update README",
            prTitle = "",
            changes = changes
        )
        assertEquals("keel/update-docs", plan.headBranch)
        assertEquals("Update README", plan.prTitle)

        // Reject pushing directly to base branch
        assertFailsWith<IllegalArgumentException> {
            GitHubPublishPlanner.validate("owner/repo", "main", "abc", "main", "msg", "title", changes)
        }
        // Reject path traversal in branch name
        assertFailsWith<IllegalArgumentException> {
            GitHubPublishPlanner.validate("owner/repo", "main", "abc", "keel/../bad", "msg", "title", changes)
        }
        // Reject empty changes
        assertFailsWith<IllegalArgumentException> {
            GitHubPublishPlanner.validate("owner/repo", "main", "abc", "keel/empty", "msg", "title", emptyList())
        }
    }

    @Test
    fun androidDeviceMemoryGateAllowsLargerModelsOnHighRamPhones() {
        val bonsai4b = ModelCatalog.byId("4B")!!
        val qwen9b = ModelCatalog.byId("9B")!!
        val gib = 1024L * 1024L * 1024L

        // 8 GB Android phone comfortably fits 4B and 9B (ternary Q2_0 ~2.55 GB)
        assertEquals(MemoryFit.COMFORTABLE, DeviceMemoryGate.evaluate(bonsai4b, 8L * gib, 4L * gib))
        assertEquals(MemoryFit.COMFORTABLE, DeviceMemoryGate.evaluate(qwen9b, 12L * gib, 8L * gib))

        // 4 GB phone cannot safely fit 9B
        assertEquals(MemoryFit.TOO_LARGE, DeviceMemoryGate.evaluate(qwen9b, 4L * gib, 2L * gib))
    }
}
