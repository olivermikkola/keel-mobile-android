package com.keelmobile.harness

import kotlinx.serialization.Serializable
import java.net.URI
import kotlin.math.sqrt

/**
 * URL allowlist and safe bounded text extraction for `read_url`.
 * Only URLs explicitly written by the user or returned by an approved web_search may be opened.
 */
object ReadUrl {
    const val MAX_RETURN_CHARS = 6_000
    private val URL_REGEX = Regex("""https?://[^\s<>")'\]]+""", RegexOption.IGNORE_CASE)
    private val SCRIPT_STYLE = Regex("""<(script|style|noscript)[^>]*>[\s\S]*?</\1>""", RegexOption.IGNORE_CASE)
    private val TAGS = Regex("""<[^>]+>""")

    fun extractAllowedUrls(messages: List<String>): Set<String> =
        messages.flatMap { text -> URL_REGEX.findAll(text).map { it.value.trimEnd('.', ',', ';', ')') } }.toSet()

    fun isAllowed(url: String, allowedUrls: Set<String>): Boolean {
        val parsed = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        if (parsed.scheme?.lowercase() !in setOf("http", "https")) return false
        val host = parsed.host?.lowercase()?.trim('[', ']') ?: return false
        if (isPrivateOrLocalHost(host)) return false
        return url.trim() in allowedUrls
    }

    private fun isPrivateOrLocalHost(host: String): Boolean {
        if (host.isEmpty() || host == "localhost" || host == "0.0.0.0" || host == "::1" || host == "::") return true
        if (host.endsWith(".local") || host.endsWith(".internal") || host.endsWith(".localhost")) return true
        if (host.startsWith("127.") || host.startsWith("10.") || host.startsWith("192.168.") ||
            host.startsWith("169.254.") || host.startsWith("0.")
        ) return true
        // 172.16.0.0/12
        if (host.startsWith("172.")) {
            val second = host.split('.').getOrNull(1)?.toIntOrNull()
            if (second != null && second in 16..31) return true
        }
        // 100.64.0.0/10 (CGNAT / Tailscale)
        if (host.startsWith("100.")) {
            val second = host.split('.').getOrNull(1)?.toIntOrNull()
            if (second != null && second in 64..127) return true
        }
        // IPv6 link-local (fe80::/10) and ULA (fc00::/7)
        if (host.startsWith("fe80:") || host.startsWith("fc") || host.startsWith("fd")) return true
        return false
    }

    fun htmlToBoundedText(html: String, maxChars: Int = MAX_RETURN_CHARS): String {
        val noScripts = SCRIPT_STYLE.replace(html, " ")
        val stripped = TAGS.replace(noScripts, " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(Regex("[ \t]+"), " ")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
        return if (stripped.length <= maxChars) stripped
        else stripped.take(maxChars) + "\n…[truncated at $maxChars chars]"
    }
}

object WebSearch {
    val tool: String =
        """{"type":"function","function":{"name":"web_search","description":"Search the web using Brave Search. Always asks the user to approve the exact query before sending anything. Returns titles, URLs and short snippets.","parameters":{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}}}"""

    @Serializable
    data class ResultItem(
        val title: String,
        val url: String,
        val snippet: String
    )

    fun formatResults(query: String, items: List<ResultItem>): String {
        if (items.isEmpty()) return "No web results found for \"$query\"."
        return buildString {
            append("Web search results for \"$query\" (untrusted data):\n")
            items.take(8).forEachIndexed { i, item ->
                append("${i + 1}. ${item.title}\n   URL: ${item.url}\n   ${item.snippet}\n")
            }
        }.trimEnd()
    }
}

@Serializable
data class McpToolDef(
    val toolName: String,
    val remoteName: String,
    val description: String,
    val readOnly: Boolean,
    val schemaLine: String
)

@Serializable
data class McpServerConfig(
    val id: String,
    val name: String,
    val endpointUrl: String,
    val requiresAuth: Boolean,
    val enabled: Boolean = false,
    val tools: List<McpToolDef> = emptyList()
)

object McpConnector {
    const val MAX_TOOLS_PER_CONNECTOR = 5

    fun isConnectorCall(name: String): Boolean = name.startsWith("mcp_")

    val builtinCatalog: List<McpServerConfig> = listOf(
        McpServerConfig("huggingface", "Hugging Face", "https://huggingface.co/mcp", requiresAuth = false),
        McpServerConfig("context7", "Context7", "https://mcp.context7.com/mcp", requiresAuth = false),
        McpServerConfig("deepwiki", "DeepWiki", "https://mcp.deepwiki.com/mcp", requiresAuth = false),
        McpServerConfig("mslearn", "Microsoft Learn", "https://learn.microsoft.com/api/mcp", requiresAuth = false),
        McpServerConfig("notion", "Notion", "https://mcp.notion.com/mcp", requiresAuth = true),
        McpServerConfig("linear", "Linear", "https://mcp.linear.app/mcp", requiresAuth = true),
        McpServerConfig("sentry", "Sentry", "https://mcp.sentry.dev/mcp", requiresAuth = true)
    )
}

/**
 * Caps active tool schemas for small models (<= 5 tools) while preserving core workspace tools.
 */
object DynamicToolSelector {
    const val MAX_ACTIVE_TOOLS = 5

    fun select(prompt: String, availableSchemaLines: List<String>, maxTools: Int = MAX_ACTIVE_TOOLS): List<String> {
        if (availableSchemaLines.size <= maxTools) return availableSchemaLines
        val lower = prompt.lowercase()
        val scored = availableSchemaLines.map { line ->
            val name = AgentPrompt.toolName(line)
            val score = scoreTool(name, lower)
            Triple(score, name, line)
        }.sortedWith(compareByDescending<Triple<Int, String, String>> { it.first }.thenBy { it.second })

        val selected = scored.take(maxTools).map { it.third }
        return selected
    }

    private fun scoreTool(name: String, lowerPrompt: String): Int {
        var score = 0
        val keywords: Map<String, List<String>> = mapOf(
            "run_sql" to listOf("csv", "table", "sql", "sum", "average", "total", "row", "column", "count", "group", "sheet"),
            "run_js" to listOf("calculate", "compute", "script", "javascript", "transform", "parse", "json", "sort"),
            "search_repo" to listOf("find", "search", "where", "grep", "locate", "symbol"),
            "read_file" to listOf("read", "open", "show", "inspect", "file", "lines"),
            "list_files" to listOf("list", "files", "folder", "directory", "what files"),
            "propose_edit" to listOf("edit", "fix", "change", "update", "replace", "modify", "refactor"),
            "create_file" to listOf("create", "make", "build", "write", "generate", "new file", "html", "page", "slides", "deck", "flashcards"),
            "read_url" to listOf("http://", "https://", "link", "webpage", "url", "article"),
            "web_search" to listOf("search the web", "latest", "news", "look up online", "google", "web"),
            "github_repos" to listOf("github", "repo", "repository", "repositories"),
            "github_open" to listOf("github", "open repo", "repository"),
            "remember" to listOf("remember", "my name", "i prefer", "always use"),
            "recall" to listOf("earlier", "previous chat", "last time", "before", "recall")
        )
        keywords[name]?.forEach { kw -> if (kw in lowerPrompt) score += 10 }
        if (name in setOf("list_files", "read_file", "search_repo")) score += 2
        if (McpConnector.isConnectorCall(name)) {
            val stem = name.removePrefix("mcp_").replace('_', ' ')
            if (stem.split(' ').any { it.length > 2 && it in lowerPrompt }) score += 15
        }
        return score
    }
}

object GitHubTools {
    val listTool: String =
        """{"type":"function","function":{"name":"github_repos","description":"List the user's connected GitHub repositories.","parameters":{"type":"object","properties":{"limit":{"type":"integer","minimum":1,"maximum":30}},"required":[]}}}"""

    val openTool: String =
        """{"type":"function","function":{"name":"github_open","description":"Open one of the user's GitHub repositories into this chat workspace, pinned to its default branch commit.","parameters":{"type":"object","properties":{"repository":{"type":"string"}},"required":["repository"]}}}"""
}

/**
 * Local semantic recall over earlier chats and generated files using cosine similarity.
 */
object Recall {
    const val MAX_RESULTS = 5
    const val PASSAGE_MAX_CHARS = 600

    val tool: String =
        """{"type":"function","function":{"name":"recall","description":"Search earlier chats and files Keel created by meaning. Returns up to 5 relevant passages with their sources.","parameters":{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}}}"""

    data class Passage(
        val source: String,
        val text: String,
        val vector: FloatArray
    )

    fun chunkLines(text: String, maxChars: Int = PASSAGE_MAX_CHARS): List<String> {
        val out = mutableListOf<String>()
        val buf = StringBuilder()
        for (line in text.lines()) {
            if (buf.isNotEmpty() && buf.length + line.length + 1 > maxChars) {
                out += buf.toString().trim()
                buf.setLength(0)
            }
            if (buf.isNotEmpty()) buf.append('\n')
            buf.append(line.take(maxChars))
        }
        if (buf.isNotBlank()) out += buf.toString().trim()
        return out
    }

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom == 0.0) 0f else (dot / denom).toFloat()
    }

    fun rank(queryVector: FloatArray, passages: List<Passage>, topK: Int = MAX_RESULTS): List<Pair<Passage, Float>> =
        passages.map { it to cosineSimilarity(queryVector, it.vector) }
            .filter { it.second > 0.1f }
            .sortedByDescending { it.second }
            .take(topK)
}
