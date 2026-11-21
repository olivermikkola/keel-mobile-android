package com.keelmobile.android

import com.keelmobile.android.data.AndroidKeystoreCredentialStore
import com.keelmobile.android.data.CredentialStore
import com.keelmobile.android.data.GitHubPublisher
import com.keelmobile.harness.GitHubPublishPlanner
import com.keelmobile.harness.WorkspaceChange
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GitHubPublisherTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun publishesBlobsTreeCommitRefAndOpensPullRequest() = runTest {
        server.enqueue(MockResponse().setBody("""{"sha":"basecommit","tree":{"sha":"basetree"}}"""))
        server.enqueue(MockResponse().setBody("""{"sha":"blob1"}"""))
        server.enqueue(MockResponse().setBody("""{"sha":"newtree"}"""))
        server.enqueue(MockResponse().setBody("""{"sha":"newcommit"}"""))
        server.enqueue(MockResponse().setBody("""{"ref":"refs/heads/keel/fix-readme"}"""))
        server.enqueue(MockResponse().setBody("""{"html_url":"https://github.com/owner/repo/pull/42"}"""))

        val creds = InMemoryCredentials(mapOf(AndroidKeystoreCredentialStore.KEY_GITHUB_TOKEN to "ghp_test_token"))
        val publisher = GitHubPublisher(
            credentials = creds,
            httpClient = OkHttpClient(),
            apiBaseUrl = server.url("").toString().trimEnd('/')
        )

        val plan = GitHubPublishPlanner.validate(
            repository = "owner/repo",
            baseBranch = "main",
            baseCommitSha = "basecommit",
            headBranch = "keel/fix-readme",
            commitMessage = "Update README and drop obsolete file",
            prTitle = "Docs cleanup",
            changes = listOf(
                WorkspaceChange("README.md", WorkspaceChange.Kind.MODIFIED, "# Clean README\n"),
                WorkspaceChange("obsolete.txt", WorkspaceChange.Kind.DELETED, null)
            )
        )

        val result = publisher.publishAndOpenPr(plan)
        assertEquals("keel/fix-readme", result.branch)
        assertEquals("newcommit", result.commitSha)
        assertEquals("https://github.com/owner/repo/pull/42", result.pullRequestUrl)

        val r1 = server.takeRequest()
        assertEquals("GET", r1.method)
        assertEquals("/repos/owner/repo/git/commits/basecommit", r1.path)
        assertEquals("Bearer ghp_test_token", r1.getHeader("Authorization"))

        val r2 = server.takeRequest()
        assertEquals("/repos/owner/repo/git/blobs", r2.path)
        assertTrue(r2.body.readUtf8().contains("# Clean README"))

        val r3 = server.takeRequest()
        assertEquals("/repos/owner/repo/git/trees", r3.path)
        val treeBody = r3.body.readUtf8()
        assertTrue(treeBody.contains(""""base_tree":"basetree""""))
        assertTrue(treeBody.contains(""""path":"obsolete.txt""""))
        assertTrue(treeBody.contains(""""sha":null"""))

        val r4 = server.takeRequest()
        assertEquals("/repos/owner/repo/git/commits", r4.path)
        val r5 = server.takeRequest()
        assertEquals("/repos/owner/repo/git/refs", r5.path)
        assertTrue(r5.body.readUtf8().contains("refs/heads/keel/fix-readme"))

        val r6 = server.takeRequest()
        assertEquals("/repos/owner/repo/pulls", r6.path)
        assertTrue(r6.body.readUtf8().contains(""""title":"Docs cleanup""""))
    }

    @Test
    fun failsClosedWithoutGitHubToken() = runTest {
        val publisher = GitHubPublisher(
            credentials = InMemoryCredentials(emptyMap()),
            httpClient = OkHttpClient(),
            apiBaseUrl = server.url("").toString().trimEnd('/')
        )
        val plan = GitHubPublishPlanner.validate(
            repository = "owner/repo",
            baseBranch = "main",
            baseCommitSha = "base",
            headBranch = "keel/test",
            commitMessage = "Test",
            prTitle = "Test",
            changes = listOf(WorkspaceChange("a.txt", WorkspaceChange.Kind.CREATED, "hi"))
        )
        assertFailsWith<IllegalStateException> {
            publisher.publishAndOpenPr(plan)
        }
    }

    private class InMemoryCredentials(initial: Map<String, String>) : CredentialStore {
        private val map = initial.toMutableMap()
        override fun put(key: String, secret: String) { map[key] = secret }
        override fun get(key: String): String? = map[key]
        override fun remove(key: String) { map.remove(key) }
    }
}
