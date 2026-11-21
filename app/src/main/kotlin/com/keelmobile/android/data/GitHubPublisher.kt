package com.keelmobile.android.data

import com.keelmobile.harness.GitHubPublishPlanner
import com.keelmobile.harness.WorkspaceChange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Explicit UI-approved GitHub publishing: creates blobs, tree, commit, new branch ref,
 * and opens a Pull Request against the pinned base branch via GitHub REST API v3.
 * Never callable directly by the model.
 */
class GitHubPublisher(
    private val credentials: CredentialStore,
    private val httpClient: OkHttpClient = AndroidToolHost.defaultClient(),
    private val apiBaseUrl: String = "https://api.github.com"
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mediaJson = "application/json; charset=utf-8".toMediaType()

    data class PublishResult(
        val branch: String,
        val commitSha: String,
        val pullRequestUrl: String
    )

    suspend fun publishAndOpenPr(plan: GitHubPublishPlanner.PublishPlan): PublishResult =
        withContext(Dispatchers.IO) {
            val token = credentials.get(AndroidKeystoreCredentialStore.KEY_GITHUB_TOKEN)
                ?: throw IllegalStateException("GitHub is not connected. Add a token in Connections.")

            // 1. Read base commit tree SHA
            val baseCommit = getJson("$apiBaseUrl/repos/${plan.repository}/git/commits/${plan.baseCommitSha}", token)
            val baseTreeSha = baseCommit["tree"]?.jsonObject?.get("sha")?.jsonPrimitive?.content
                ?: throw IllegalStateException("Could not resolve base tree SHA.")

            // 2. Build tree entries (creating blobs for added/modified files, null sha for deletions)
            val treeEntries = plan.changes.map { change ->
                when (change.kind) {
                    WorkspaceChange.Kind.DELETED -> JsonObject(
                        mapOf(
                            "path" to JsonPrimitive(change.path),
                            "mode" to JsonPrimitive("100644"),
                            "type" to JsonPrimitive("blob"),
                            "sha" to JsonNull
                        )
                    )
                    WorkspaceChange.Kind.CREATED, WorkspaceChange.Kind.MODIFIED -> {
                        val blobResp = postJson(
                            "$apiBaseUrl/repos/${plan.repository}/git/blobs",
                            token,
                            JsonObject(
                                mapOf(
                                    "content" to JsonPrimitive(change.content.orEmpty()),
                                    "encoding" to JsonPrimitive("utf-8")
                                )
                            )
                        )
                        val blobSha = blobResp["sha"]?.jsonPrimitive?.content
                            ?: throw IllegalStateException("Failed to create blob for ${change.path}")
                        JsonObject(
                            mapOf(
                                "path" to JsonPrimitive(change.path),
                                "mode" to JsonPrimitive("100644"),
                                "type" to JsonPrimitive("blob"),
                                "sha" to JsonPrimitive(blobSha)
                            )
                        )
                    }
                }
            }

            // 3. Create new Git tree
            val treeResp = postJson(
                "$apiBaseUrl/repos/${plan.repository}/git/trees",
                token,
                JsonObject(
                    mapOf(
                        "base_tree" to JsonPrimitive(baseTreeSha),
                        "tree" to JsonArray(treeEntries)
                    )
                )
            )
            val newTreeSha = treeResp["sha"]?.jsonPrimitive?.content
                ?: throw IllegalStateException("Failed to create Git tree.")

            // 4. Create commit on top of pinned baseCommitSha
            val commitResp = postJson(
                "$apiBaseUrl/repos/${plan.repository}/git/commits",
                token,
                JsonObject(
                    mapOf(
                        "message" to JsonPrimitive(plan.commitMessage),
                        "tree" to JsonPrimitive(newTreeSha),
                        "parents" to JsonArray(listOf(JsonPrimitive(plan.baseCommitSha)))
                    )
                )
            )
            val newCommitSha = commitResp["sha"]?.jsonPrimitive?.content
                ?: throw IllegalStateException("Failed to create commit.")

            // 5. Create branch ref
            postJson(
                "$apiBaseUrl/repos/${plan.repository}/git/refs",
                token,
                JsonObject(
                    mapOf(
                        "ref" to JsonPrimitive("refs/heads/${plan.headBranch}"),
                        "sha" to JsonPrimitive(newCommitSha)
                    )
                )
            )

            // 6. Open Pull Request
            val prBody = buildString {
                append("Published from Keel Mobile (Android) after explicit user review.\n\n")
                append("Changed files:\n")
                plan.changes.forEach { append("- `${it.path}` (${it.kind.name.lowercase()})\n") }
            }
            val prResp = postJson(
                "$apiBaseUrl/repos/${plan.repository}/pulls",
                token,
                JsonObject(
                    mapOf(
                        "title" to JsonPrimitive(plan.prTitle),
                        "head" to JsonPrimitive(plan.headBranch),
                        "base" to JsonPrimitive(plan.baseBranch),
                        "body" to JsonPrimitive(prBody)
                    )
                )
            )
            val prUrl = prResp["html_url"]?.jsonPrimitive?.content
                ?: "https://github.com/${plan.repository}/pull/new/${plan.headBranch}"

            PublishResult(plan.headBranch, newCommitSha, prUrl)
        }

    private fun getJson(url: String, token: String): JsonObject {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("GitHub API returned HTTP ${resp.code}.")
            return json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
        }
    }

    private fun postJson(url: String, token: String, payload: JsonObject): JsonObject {
        val req = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(mediaJson))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("GitHub API returned HTTP ${resp.code}: ${resp.body?.string()?.take(180)}")
            return json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
        }
    }
}
