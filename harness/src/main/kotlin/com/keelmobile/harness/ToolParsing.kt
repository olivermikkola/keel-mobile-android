package com.keelmobile.harness

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.StringReader
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

sealed class DirectorToolError(message: String) : Exception(message) {
    class InvalidCall(val reason: String) : DirectorToolError("Invalid tool call: $reason")
    object ApprovalRequired : DirectorToolError(
        "Not executed. Review and apply proposed changes in the UI. Publishing and CI need a separate UI decision; a model tool call cannot authorize them."
    )
}

/**
 * Recovers a single Qwen-style tool call from untidy small-model output.
 * Never invents, reorders or edits arguments; DirectorCall.parse still decides.
 */
object ToolCallText {
    data class Normalized(
        val text: String,
        val repaired: Boolean,
        val droppedTrailing: String
    )

    const val BROKEN_ONLY =
        "The model wrote a broken tool request instead of an answer. Try again, or pick a model made for tool use."

    private val STRAY_BLOCKS = listOf(
        Regex("""<tool_call>[\s\S]*?(?:</tool_call>|\z)"""),
        Regex("""<function(?:=|\s|>)[\s\S]*?(?:</function>|\z)""")
    )

    private val STRAY_TAG = Regex("""</?(?:think|tool_call|function|param(?:eter)?)(?:[= ][^>\n]*)?>""")

    fun afterThink(output: String): String {
        val idx = output.lastIndexOf("</think>")
        if (idx < 0) return ""
        return output.substring(idx + "</think>".length).trim()
    }

    fun visibleReply(output: String): String {
        val idx = output.lastIndexOf("</think>")
        if (idx >= 0) {
            val before = output.substring(0, idx)
            if (!before.contains("<tool_call>") && !before.contains("<function")) {
                val after = output.substring(idx + "</think>".length).trim()
                if (after.isNotEmpty()) return after
                val cleanedBefore = before.replace("<think>", "").trim()
                if (cleanedBefore.isNotEmpty()) return cleanedBefore
            }
        }
        if (output.startsWith("<think>")) {
            return output.removePrefix("<think>").trim()
        }
        return output
    }

    fun cleanReply(output: String): Pair<String, Boolean> {
        val segments = output.split("```").toMutableList()
        var removed = false
        var i = 0
        while (i < segments.size) {
            var seg = segments[i]
            for (pattern in STRAY_BLOCKS) {
                val cleaned = pattern.replace(seg, "")
                if (cleaned != seg) {
                    removed = true
                    seg = cleaned
                }
            }
            segments[i] = seg
            i += 2
        }
        var text = segments.joinToString("```")
        val closeThink = text.lastIndexOf("</think>")
        if (closeThink >= 0 && !text.substring(0, closeThink).contains("```")) {
            val after = text.substring(closeThink + "</think>".length).trim()
            if (after.isNotEmpty()) text = after
        }
        val parts = text.split("```").mapIndexed { index, part ->
            if (index % 2 != 0) part
            else {
                val cleaned = STRAY_TAG.replace(part, "")
                if (cleaned != part) removed = true
                cleaned
            }
        }
        text = parts.joinToString("```")
        val visible = visibleReply(text).trim()
        val finalText = if (visible.isEmpty() && removed) BROKEN_ONLY else visible
        return finalText to removed
    }

    fun normalize(output: String): Normalized {
        val closeThink = output.lastIndexOf("</think>")
        if (closeThink >= 0) {
            val before = output.substring(0, closeThink)
            val after = output.substring(closeThink + "</think>".length)
            if (!before.contains("<tool_call>") &&
                !before.contains("<function") &&
                (after.contains("<tool_call>") || after.contains("<function="))
            ) {
                val call = normalize(after.trim())
                return Normalized(call.text, true, call.droppedTrailing)
            }
        }

        val unchanged = Normalized(output, false, "")
        if (occurrences("<tool_call>", output) == 0 &&
            occurrences("</tool_call>", output) == 0 &&
            occurrences("<function=", output) == 1 &&
            occurrences("</function>", output) == 1
        ) {
            val open = output.indexOf("<function=")
            val end = output.indexOf("</function>")
            if (open >= 0 && end >= open) {
                val endAfter = end + "</function>".length
                val wrapped = output.substring(0, open) +
                    "<tool_call>\n" +
                    output.substring(open, endAfter) +
                    "\n</tool_call>" +
                    output.substring(endAfter)
                val call = normalize(wrapped)
                return Normalized(call.text, true, call.droppedTrailing)
            }
        }

        if (occurrences("<tool_call>", output) != 1 ||
            occurrences("<function=", output) != 1 ||
            occurrences("</function>", output) != 1 ||
            occurrences("</tool_call>", output) > 1
        ) {
            return unchanged
        }

        val open = output.indexOf("<tool_call>")
        val functionEnd = output.indexOf("</function>")
        if (open < 0 || functionEnd < open) return unchanged
        val functionEndAfter = functionEnd + "</function>".length

        var end = functionEndAfter
        var closedByModel = false
        val close = output.indexOf("</tool_call>", functionEndAfter)
        if (close >= 0) {
            val between = output.substring(functionEndAfter, close)
            if (between.trim().isEmpty()) {
                end = close + "</tool_call>".length
                closedByModel = true
            } else {
                return unchanged
            }
        }

        val leading = stripFenceOpening(output.substring(0, open))
        val trailing = stripFenceClosing(output.substring(end))
        val call = output.substring(open, end) + if (closedByModel) "" else "\n</tool_call>"
        val text = (if (leading.isEmpty()) "" else "$leading\n") + call
        val droppedTrailing = trailing.trim()
        val repaired = text != output.trim()
        return Normalized(text, repaired, droppedTrailing)
    }

    fun occurrences(needle: String, text: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var idx = 0
        while (true) {
            val found = text.indexOf(needle, idx)
            if (found < 0) break
            count++
            idx = found + needle.length
        }
        return count
    }

    private fun stripFenceOpening(text: String): String {
        val lines = text.split("\n").toMutableList()
        while (lines.isNotEmpty() && lines.last().trim().isEmpty()) lines.removeAt(lines.lastIndex)
        if (lines.isNotEmpty() && lines.last().trim().startsWith("```")) lines.removeAt(lines.lastIndex)
        return lines.joinToString("\n").trim()
    }

    private fun stripFenceClosing(text: String): String {
        val lines = text.split("\n").toMutableList()
        while (lines.isNotEmpty() && lines.first().trim().isEmpty()) lines.removeAt(0)
        if (lines.isNotEmpty() && lines.first().trim() == "```") lines.removeAt(0)
        return lines.joinToString("\n")
    }
}

data class DirectorCall(
    val name: String,
    val arguments: Map<String, String>,
    val rawArguments: Map<String, String> = emptyMap()
) {
    val label: String
        get() = when (name) {
            "list_files" -> "List ${arguments["path"].takeUnless { it.isNullOrEmpty() } ?: "/"}"
            "search_file" -> "Search '${arguments["query"].orEmpty()}' in ${arguments["path"].orEmpty()}"
            "search_repo" -> {
                val p = arguments["path"].orEmpty()
                "Search ${if (p.isEmpty() || p == ".") "workspace" else p} · ${arguments["query"].orEmpty()}"
            }
            "read_file" -> {
                val start = arguments["start_line"] ?: "1"
                val count = arguments["count"]
                if (count == null) "Read ${arguments["path"].orEmpty()} · from line $start"
                else "Read ${arguments["path"].orEmpty()} · lines $start+($count)"
            }
            "stage_file_edit", "propose_edit" -> "Propose edit · ${arguments["path"].orEmpty()}"
            "create_file" -> "Propose create · ${arguments["path"].orEmpty()}"
            "delete_file" -> "Propose delete · ${arguments["path"].orEmpty()}"
            "rename_file" -> "Propose rename · ${arguments["path"].orEmpty()} → ${arguments["destination_path"].orEmpty()}"
            "run_sql" -> "Run SQL"
            "run_js" -> "Run JavaScript"
            "web_search" -> "Search the web"
            "read_url" -> {
                val host = runCatching { URI(arguments["url"].orEmpty()).host }.getOrNull().orEmpty()
                "Read web page · $host"
            }
            "github_repos" -> "Look at your GitHub repositories"
            "github_open" -> "Open ${arguments["repository"] ?: "a repository"} from GitHub"
            "remember" -> "Remember about you"
            "recall" -> "Look through earlier chats and files"
            "run_ci" -> "Run CI on GitHub Actions"
            "finish_task" -> "Create Pull Request · ${arguments["title"] ?: "Task complete"}"
            else -> if (McpConnector.isConnectorCall(name)) "MCP · ${name.removePrefix("mcp_")}" else "Execute $name"
        }

    companion object {
        private val CALL_REGEX = Regex(
            """\A(?:[^<]*\s*)?<tool_call>\s*<function=([a-z_][a-z0-9_]*)>([\s\S]*?)</function>\s*</tool_call>\s*\z"""
        )
        private val PARAM_REGEX = Regex(
            """\A<parameter=([a-z_][a-z0-9_]*)>([\s\S]*?)</parameter>"""
        )

        private val ALLOWED_TOOLS = setOf(
            "list_files", "search_file", "search_repo", "read_file",
            "run_sql", "run_js",
            "propose_edit", "stage_file_edit", "create_file", "delete_file", "rename_file",
            "run_ci", "finish_task",
            "read_url", "web_search",
            "github_repos", "github_open",
            "remember", "recall"
        )

        fun unwrapLine(value: String): String {
            var s = value
            if (s.startsWith("\r\n")) s = s.drop(2)
            else if (s.startsWith("\n")) s = s.drop(1)
            if (s.endsWith("\r\n")) s = s.dropLast(2)
            else if (s.endsWith("\n")) s = s.dropLast(1)
            return s
        }

        fun unwrapSingleKeyJson(value: String, key: String): String {
            val trimmed = value.trim()
            if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return value
            return try {
                val obj = Json.parseToJsonElement(trimmed).jsonObject
                if (obj.size == 1) {
                    val v = obj[key] as? JsonPrimitive
                    if (v != null && v.isString) v.content else value
                } else value
            } catch (_: Exception) {
                value
            }
        }

        fun parse(output: String): DirectorCall? {
            val text = output.trim()
            if (!text.contains("<tool_call") && !text.contains("<function=") && !text.contains("</tool_call>")) {
                return null
            }
            val match = CALL_REGEX.matchEntire(text)
                ?: throw DirectorToolError.InvalidCall("Malformed <tool_call> tags.")
            val name = match.groupValues[1]
            val body = match.groupValues[2]
            if (name !in ALLOWED_TOOLS && !McpConnector.isConnectorCall(name)) {
                throw DirectorToolError.InvalidCall("Unknown tool name: '$name'.")
            }

            var remaining = body.trim()
            val args = linkedMapOf<String, String>()
            val raw = linkedMapOf<String, String>()
            while (remaining.isNotEmpty()) {
                val part = PARAM_REGEX.find(remaining)
                    ?: throw DirectorToolError.InvalidCall("Malformed <parameter> tag inside $name.")
                val key = part.groupValues[1]
                if (key in args) throw DirectorToolError.InvalidCall("Duplicate argument: $key.")
                val rawValue = part.groupValues[2]
                if (key in setOf("sql", "code") &&
                    listOf("<parameter=", "<function=", "<tool_call").any { rawValue.contains(it) }
                ) {
                    throw DirectorToolError.InvalidCall("Nested tool tag inside $key parameter.")
                }
                val unescaped = rawValue
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&amp;", "&")
                when {
                    key in setOf("old_text", "new_text") -> {
                        args[key] = unwrapLine(unescaped)
                        raw[key] = unwrapLine(rawValue)
                    }
                    key in setOf("sql", "code") -> {
                        args[key] = unwrapSingleKeyJson(unwrapLine(unescaped), key)
                    }
                    McpConnector.isConnectorCall(name) -> {
                        args[key] = unwrapLine(unescaped)
                    }
                    key == "query" -> {
                        args[key] = unescaped
                    }
                    key == "url" -> {
                        args[key] = unescaped.trim()
                    }
                    else -> {
                        args[key] = rawValue.trim()
                    }
                }
                remaining = remaining.substring(part.range.last + 1).trim()
            }
            return DirectorCall(name = name, arguments = args, rawArguments = raw)
        }
    }
}

/**
 * Converts MiniCPM5's native XML (<function name="..."><param name="...">...</param></function>)
 * into Keel's strict canonical <tool_call> format.
 */
object NativeToolCall {
    private val IDENT_REGEX = Regex("^[a-z_][a-z0-9_]*$")

    fun normalize(output: String, family: ModelFamily): String {
        if (family != ModelFamily.MINI_CPM5) return output
        if (!output.contains("<function") && !output.contains("<param")) return output

        var text = output.trim()
        val closeThink = text.lastIndexOf("</think>")
        if (closeThink >= 0) {
            val before = text.substring(0, closeThink)
            if (!before.contains("<function") && !before.contains("<param")) {
                text = text.substring(closeThink + "</think>".length).trim()
            }
        }
        if (text.startsWith("```") && text.endsWith("```")) {
            val nl = text.indexOf('\n')
            if (nl >= 0) {
                text = text.substring(nl + 1, text.length - 3).trim()
            }
        }
        if (text.toByteArray(Charsets.UTF_8).size > 96 * 1024) {
            throw DirectorToolError.InvalidCall("Malformed native tool call.")
        }
        val start = text.indexOf("<function")
        if (start < 0 || text.substring(0, start).contains('<')) {
            throw DirectorToolError.InvalidCall("Malformed native tool call.")
        }

        val xmlText = "<keel_native_call>${text.substring(start)}</keel_native_call>"
        val factory = DocumentBuilderFactory.newInstance().apply {
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        val doc = try {
            factory.newDocumentBuilder().parse(InputSource(StringReader(xmlText)))
        } catch (_: Exception) {
            throw DirectorToolError.InvalidCall("Malformed native tool call.")
        }

        val root = doc.documentElement ?: throw DirectorToolError.InvalidCall("Malformed native tool call.")
        var fnElement: Element? = null
        val children = root.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            when (node.nodeType) {
                Node.ELEMENT_NODE -> {
                    val el = node as Element
                    if (el.tagName != "function" || fnElement != null) {
                        throw DirectorToolError.InvalidCall("Malformed native tool call.")
                    }
                    fnElement = el
                }
                Node.TEXT_NODE -> {
                    if (node.textContent.trim().isNotEmpty()) {
                        throw DirectorToolError.InvalidCall("Malformed native tool call.")
                    }
                }
                else -> throw DirectorToolError.InvalidCall("Malformed native tool call.")
            }
        }
        val fn = fnElement ?: throw DirectorToolError.InvalidCall("Malformed native tool call.")
        val fnName = fn.getAttribute("name")
        if (fn.attributes.length != 1 || !IDENT_REGEX.matches(fnName)) {
            throw DirectorToolError.InvalidCall("Malformed native tool call.")
        }

        val arguments = linkedMapOf<String, String>()
        val params = fn.childNodes
        for (i in 0 until params.length) {
            val node = params.item(i)
            when (node.nodeType) {
                Node.ELEMENT_NODE -> {
                    val el = node as Element
                    if (el.tagName != "param" || el.attributes.length != 1) {
                        throw DirectorToolError.InvalidCall("Malformed native tool call.")
                    }
                    val pName = el.getAttribute("name")
                    if (!IDENT_REGEX.matches(pName) || pName in arguments) {
                        throw DirectorToolError.InvalidCall("Malformed native tool call.")
                    }
                    arguments[pName] = el.textContent ?: ""
                }
                Node.TEXT_NODE -> {
                    if (node.textContent.trim().isNotEmpty()) {
                        throw DirectorToolError.InvalidCall("Malformed native tool call.")
                    }
                }
                else -> throw DirectorToolError.InvalidCall("Malformed native tool call.")
            }
        }

        val canonical = canonicalCall(fnName, arguments)
        DirectorCall.parse(canonical)
        return canonical
    }

    fun canonicalCall(name: String, arguments: Map<String, String>): String {
        fun escape(v: String): String = v
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

        val sb = StringBuilder()
        sb.append("<tool_call>\n<function=").append(name).append('>')
        for ((key, value) in arguments.toSortedMap()) {
            val encoded = if (key == "path" || key == "destination_path") value else escape(value)
            val pad = if (key in setOf("code", "sql", "old_text", "new_text")) "\n" else ""
            sb.append("\n<parameter=").append(key).append('>').append(pad).append(encoded).append(pad).append("</parameter>")
        }
        sb.append("\n</function>\n</tool_call>")
        return sb.toString()
    }

    fun renderCanonical(text: String): String {
        val call = runCatching { DirectorCall.parse(text) }.getOrNull() ?: return text
        val params = call.arguments.toSortedMap().entries.joinToString("") { (k, v) ->
            val escaped = v.replace("]]>", "]]]]><![CDATA[>")
            """<param name="$k"><![CDATA[$escaped]]></param>"""
        }
        return """<function name="${call.name}">$params</function>"""
    }
}
