package com.keelmobile.android.data

import com.keelmobile.harness.ReadUrl
import com.keelmobile.harness.TaskWorkspace
import com.keelmobile.harness.ToolHost
import com.keelmobile.harness.WebSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Real Android ToolHost wiring:
 * - read_url: bounded HTTP GET (<= 512 KB HTML -> <= 6,000 chars text)
 * - web_search: Brave Search API using Android Keystore secret, ONLY after UI approval
 * - remember: ONLY after explicit UI approval
 * - github_repos / github_open: GitHub REST API v3 + pinned commit gzip tarball import
 *   into the task's SQLite virtual disk (zero checkout files on disk)
 * - mcp_*: JSON-RPC 2.0 Streamable HTTP calls to enabled MCP connectors
 */
class AndroidToolHost(
    private val credentials: CredentialStore,
    private val workspaceProvider: () -> TaskWorkspace,
    private val onRepositoryOpened: (name: String, branch: String, sha: String) -> Unit,
    private val onAskWebSearchApproval: suspend (query: String) -> Boolean,
    private val onAskMemoryApproval: suspend (fact: String) -> Boolean,
    private val onRecall: (suspend (query: String) -> String)? = null,
    private val httpClient: OkHttpClient = defaultClient()
) : ToolHost {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun readUrl(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "KeelMobile-Android/0.1")
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("Could not open page (HTTP ${resp.code}).")
            }
            val body = resp.body ?: throw IllegalStateException("Empty response.")
            val bytes = body.source().use { src ->
                src.request(MAX_HTML_BYTES)
                val avail = minOf(src.buffer.size, MAX_HTML_BYTES)
                src.buffer.readByteArray(avail)
            }
            ReadUrl.htmlToBoundedText(bytes.toString(Charsets.UTF_8))
        }
    }

    override suspend fun requestWebSearchApproval(query: String): Boolean =
        onAskWebSearchApproval(query)

    override suspend fun searchWeb(query: String): List<WebSearch.ResultItem> = withContext(Dispatchers.IO) {
        val apiKey = credentials.get(AndroidKeystoreCredentialStore.KEY_BRAVE_SEARCH)
            ?: throw IllegalStateException("Brave Search API key is not set. Add it in Connections.")
        val url = "https://api.search.brave.com/res/v1/web/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("count", "6")
            .build()
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("X-Subscription-Token", apiKey)
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("Brave Search failed (HTTP ${resp.code}).")
            val text = resp.body?.string().orEmpty()
            val root = json.parseToJsonElement(text).jsonObject
            val results = root["web"]?.jsonObject?.get("results")?.jsonArray ?: return@withContext emptyList()
            results.mapNotNull { el ->
                val obj = el.jsonObject
                val title = obj["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val itemUrl = obj["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val desc = obj["description"]?.jsonPrimitive?.content.orEmpty()
                WebSearch.ResultItem(title, itemUrl, desc)
            }
        }
    }

    override suspend fun requestMemoryApproval(fact: String): Boolean =
        onAskMemoryApproval(fact)

    override suspend fun listGitHubRepos(limit: Int): String = withContext(Dispatchers.IO) {
        val token = credentials.get(AndroidKeystoreCredentialStore.KEY_GITHUB_TOKEN)
            ?: throw IllegalStateException("GitHub is not connected. Add a token in Connections.")
        val capped = limit.coerceIn(1, 30)
        val req = Request.Builder()
            .url("https://api.github.com/user/repos?sort=updated&per_page=$capped")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("GitHub returned HTTP ${resp.code}.")
            val arr = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonArray
            if (arr.isEmpty()) return@withContext "No repositories found."
            arr.joinToString("\n") { el ->
                val o = el.jsonObject
                val full = o["full_name"]?.jsonPrimitive?.content.orEmpty()
                val branch = o["default_branch"]?.jsonPrimitive?.content ?: "main"
                val priv = if (o["private"]?.jsonPrimitive?.content == "true") "private" else "public"
                "- $full ($branch, $priv)"
            }
        }
    }

    override suspend fun openGitHubRepo(repository: String): String = withContext(Dispatchers.IO) {
        val token = credentials.get(AndroidKeystoreCredentialStore.KEY_GITHUB_TOKEN)
            ?: throw IllegalStateException("GitHub is not connected. Add a token in Connections.")
        val cleanRepo = repository.trim().trim('/')
        if (!Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$").matches(cleanRepo)) {
            throw IllegalArgumentException("Repository must be in owner/name format.")
        }

        // 1. Fetch default branch and pin to exact HEAD commit SHA
        val repoReq = Request.Builder()
            .url("https://api.github.com/repos/$cleanRepo")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .build()
        val defaultBranch = httpClient.newCall(repoReq).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("Could not open $cleanRepo (HTTP ${resp.code}).")
            json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject["default_branch"]
                ?.jsonPrimitive?.content ?: "main"
        }

        val commitReq = Request.Builder()
            .url("https://api.github.com/repos/$cleanRepo/commits/$defaultBranch")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .build()
        val commitSha = httpClient.newCall(commitReq).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("Could not resolve $defaultBranch commit (HTTP ${resp.code}).")
            json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject["sha"]
                ?.jsonPrimitive?.content ?: throw IllegalStateException("Missing commit SHA.")
        }

        // 2. Download gzip tarball at that pinned commit and import directly into SQLite TaskWorkspace
        val tarReq = Request.Builder()
            .url("https://api.github.com/repos/$cleanRepo/tarball/$commitSha")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .build()
        val imported = httpClient.newCall(tarReq).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("Could not download archive (HTTP ${resp.code}).")
            val bytes = resp.body?.bytes() ?: throw IllegalStateException("Empty archive.")
            workspaceProvider().importGzipTar(bytes)
        }

        onRepositoryOpened(cleanRepo, defaultBranch, commitSha)
        "Opened $cleanRepo@$defaultBranch (${commitSha.take(7)}) into this task workspace ($imported files imported)."
    }

    override suspend fun recall(query: String): String =
        onRecall?.invoke(query) ?: super.recall(query)

    override suspend fun callMcpTool(name: String, arguments: Map<String, String>): String =
        withContext(Dispatchers.IO) {
            val parts = name.removePrefix("mcp_").split('_', limit = 2)
            if (parts.size < 2) throw IllegalArgumentException("Malformed MCP tool name: $name")
            val serverId = parts[0]
            val remoteTool = parts[1]
            val server = com.keelmobile.harness.McpConnector.builtinCatalog.firstOrNull { it.id == serverId }
                ?: throw IllegalStateException("Unknown MCP connector: $serverId")
            val argJson = JsonObject(arguments.mapValues { JsonPrimitive(it.value) })
            val payload = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$remoteTool","arguments":$argJson}}"""
            val reqBuilder = Request.Builder()
                .url(server.endpointUrl)
                .post(payload.toRequestBody("application/json".toMediaType()))
                .header("Accept", "application/json")
            credentials.get(AndroidKeystoreCredentialStore.mcpTokenKey(serverId))?.let { token ->
                reqBuilder.header("Authorization", "Bearer $token")
            }
            httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("MCP server ${server.name} returned HTTP ${resp.code}.")
                resp.body?.string()?.take(8_000) ?: "(empty response)"
            }
        }

    companion object {
        private const val MAX_HTML_BYTES = 512L * 1024L

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}
