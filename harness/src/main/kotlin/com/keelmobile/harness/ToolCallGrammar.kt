package com.keelmobile.harness

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Generates llama.cpp lazy GBNF grammars from the prompt's <tools> block so
 * small models can only emit valid tool names, valid parameters, required fields,
 * and digits for integers. Exact port of Keel Mobile's ToolCallGrammar.swift.
 */
data class ToolCallGrammar(
    val text: String,
    val triggerPatterns: List<String>
) {
    private enum class ParamKind(val ruleName: String) {
        INTEGER("integer"),
        LINE("line"),
        TEXT("text"),
        CODE("code")
    }

    private data class ParamSchema(
        val name: String,
        val kind: ParamKind,
        val required: Boolean
    )

    private data class ToolSchema(
        val name: String,
        val parameters: List<ParamSchema>
    )

    private sealed class Node {
        data class Literal(val value: String) : Node()
        data class Rule(val name: String) : Node()
        data class Chars(val chars: Set<Char>, val negated: Boolean) : Node()
        data class Sequence(val items: List<Node>) : Node()
        data class Choice(val items: List<Node>) : Node()
        data class Repeated(val item: Node, val min: Int, val max: Int?) : Node()
    }

    companion object {
        const val ROOT = "root"

        private val WHITESPACE = Node.Repeated(Node.Chars(setOf(' ', '\t', '\n'), negated = false), 0, 4)
        private val DIGIT = Node.Chars(('0'..'9').toSet(), negated = false)

        fun make(tools: List<String>, family: ModelFamily): ToolCallGrammar? {
            val seen = mutableSetOf<String>()
            val schemas = tools.mapNotNull(::parseSchema).filter { seen.add(it.name) }
            if (schemas.isEmpty()) return null
            return if (family == ModelFamily.MINI_CPM5) native(schemas) else qwen(schemas)
        }

        fun makeFromPrompt(prompt: String, family: ModelFamily): ToolCallGrammar? {
            val openIdx = prompt.indexOf("<tools>\n")
            if (openIdx < 0) return null
            val bodyStart = openIdx + "<tools>\n".length
            val closeIdx = prompt.indexOf("\n</tools>", bodyStart)
            if (closeIdx < 0) return null
            val lines = prompt.substring(bodyStart, closeIdx).split("\n")
            return make(lines, family)
        }

        fun ruleName(name: String): String =
            "t-" + name.lowercase().map { c ->
                if ((c in 'a'..'z') || (c in '0'..'9')) c else '-'
            }.joinToString("")

        private fun parseSchema(line: String): ToolSchema? {
            return try {
                val root = Json.parseToJsonElement(line.trim()).jsonObject
                val fn = root["function"]?.jsonObject ?: root
                val name = fn["name"]?.jsonPrimitive?.content ?: return null
                if (!Regex("^[a-z_][a-z0-9_]*$").matches(name)) return null
                val paramsObj = fn["parameters"]?.jsonObject
                val props = paramsObj?.get("properties")?.jsonObject ?: emptyMap()
                val required = paramsObj?.get("required")?.jsonArray?.mapNotNull {
                    runCatching { it.jsonPrimitive.content }.getOrNull()
                }?.toSet() ?: emptySet()

                val parameters = props.entries.sortedBy { it.key }.mapNotNull { (pName, pElem) ->
                    if (!Regex("^[a-z_][a-z0-9_]*$").matches(pName)) return@mapNotNull null
                    val pObj = runCatching { pElem.jsonObject }.getOrNull()
                    val type = pObj?.get("type")?.jsonPrimitive?.content ?: "string"
                    val kind = when {
                        type == "integer" -> ParamKind.INTEGER
                        pName in setOf("code", "sql") -> ParamKind.CODE
                        pName in setOf("old_text", "new_text", "fact", "body") -> ParamKind.TEXT
                        else -> ParamKind.LINE
                    }
                    ParamSchema(pName, kind, pName in required)
                }
                ToolSchema(name, parameters)
            } catch (_: Exception) {
                null
            }
        }

        private fun qwen(schemas: List<ToolSchema>): ToolCallGrammar {
            val rules = mutableListOf<Pair<String, Node>>(
                ROOT to Node.Choice(
                    listOf(
                        Node.Sequence(
                            listOf(
                                Node.Literal("<tool_call>"),
                                Node.Rule("ws"),
                                Node.Rule("call"),
                                Node.Rule("ws"),
                                Node.Literal("</tool_call>")
                            )
                        ),
                        Node.Rule("call")
                    )
                ),
                "call" to Node.Choice(schemas.map { Node.Rule(ruleName(it.name)) })
            )
            for (schema in schemas) {
                val body = mutableListOf<Node>(Node.Literal("<function=${schema.name}>"))
                for (param in schema.parameters) {
                    val pRule = ruleName("${schema.name}-${param.name}")
                    rules += pRule to Node.Sequence(
                        listOf(
                            Node.Literal("<parameter=${param.name}>"),
                            Node.Rule(param.kind.ruleName),
                            Node.Literal("</parameter>")
                        )
                    )
                    val item = Node.Sequence(listOf(Node.Rule("ws"), Node.Rule(pRule)))
                    body += if (param.required) item else Node.Repeated(item, 0, 1)
                }
                rules += ruleName(schema.name) to Node.Sequence(body + listOf(Node.Rule("ws"), Node.Literal("</function>")))
            }

            val used = schemas.flatMap { s -> s.parameters.map { it.kind } }.toSet()
            rules += "ws" to WHITESPACE
            if (ParamKind.INTEGER in used) {
                rules += "integer" to Node.Sequence(
                    listOf(Node.Rule("ws"), Node.Repeated(DIGIT, 1, 9), Node.Rule("ws"))
                )
            }
            if (ParamKind.LINE in used) {
                val nl = Node.Repeated(Node.Literal("\n"), 0, 1)
                rules += "line" to Node.Sequence(
                    listOf(nl, Node.Repeated(Node.Chars(setOf('<', '\n', '\r'), negated = true), 1, null), nl)
                )
            }
            if (ParamKind.TEXT in used) {
                rules += excluding(listOf("</parameter>"), "text")
            }
            if (ParamKind.CODE in used) {
                rules += excluding(listOf("</parameter>", "<parameter=", "<function=", "<tool_call"), "code")
            }

            val rendered = rules.joinToString("\n") { (n, node) -> "$n ::= ${render(node, top = true)}" } + "\n"
            return ToolCallGrammar(rendered, listOf("(<tool_call>)", "(<function)"))
        }

        private fun native(schemas: List<ToolSchema>): ToolCallGrammar {
            val rules = mutableListOf<Pair<String, Node>>(
                ROOT to Node.Choice(schemas.map { Node.Rule(ruleName(it.name)) })
            )
            for (schema in schemas) {
                val body = mutableListOf<Node>(Node.Literal("""<function name="${schema.name}">"""))
                for (param in schema.parameters) {
                    val pRule = ruleName("${schema.name}-${param.name}")
                    val valRule = when (param.kind) {
                        ParamKind.INTEGER -> "xml-integer"
                        ParamKind.LINE -> "xml-line"
                        else -> "xml-text"
                    }
                    rules += pRule to Node.Sequence(
                        listOf(
                            Node.Literal("""<param name="${param.name}">"""),
                            Node.Rule(valRule),
                            Node.Literal("</param>")
                        )
                    )
                    val item = Node.Sequence(listOf(Node.Rule("ws"), Node.Rule(pRule)))
                    body += if (param.required) item else Node.Repeated(item, 0, 1)
                }
                rules += ruleName(schema.name) to Node.Sequence(body + listOf(Node.Rule("ws"), Node.Literal("</function>")))
            }

            val entity = Node.Sequence(
                listOf(
                    Node.Literal("&"),
                    Node.Choice(listOf("lt", "gt", "amp", "quot", "apos").map { Node.Literal(it) }),
                    Node.Literal(";")
                )
            )
            rules += "ws" to WHITESPACE
            rules += "xml-integer" to Node.Sequence(
                listOf(Node.Rule("ws"), Node.Repeated(DIGIT, 1, 9), Node.Rule("ws"))
            )
            rules += "xml-text" to Node.Repeated(
                Node.Choice(
                    listOf(
                        Node.Chars(setOf('<', '&', '>'), negated = true),
                        entity,
                        Node.Sequence(listOf(Node.Literal("<![CDATA["), Node.Rule("cdata"), Node.Literal("]]>")))
                    )
                ),
                0,
                null
            )
            rules += "xml-line" to Node.Repeated(
                Node.Choice(
                    listOf(
                        Node.Chars(setOf('<', '&', '>', '\n', '\r'), negated = true),
                        entity,
                        Node.Sequence(listOf(Node.Literal("<![CDATA["), Node.Rule("cdata-line"), Node.Literal("]]>")))
                    )
                ),
                1,
                null
            )
            rules += excluding(listOf("]]>"), "cdata")
            rules += excluding(listOf("]]>", "\n", "\r"), "cdata-line")

            val rendered = rules.joinToString("\n") { (n, node) -> "$n ::= ${render(node, top = true)}" } + "\n"
            return ToolCallGrammar(rendered, listOf("(<function)"))
        }

        /**
         * Aho-Corasick automaton encoded as right-recursive GBNF rules matching any text
         * that contains none of `patterns`.
         */
        private fun excluding(patterns: List<String>, prefix: String): List<Pair<String, Node>> {
            val next = mutableListOf<MutableMap<Char, Int>>(mutableMapOf())
            val terminal = mutableListOf(false)
            for (pattern in patterns) {
                var state = 0
                for (ch in pattern) {
                    val known = next[state][ch]
                    if (known != null) {
                        state = known
                    } else {
                        next.add(mutableMapOf())
                        terminal.add(false)
                        val id = next.lastIndex
                        next[state][ch] = id
                        state = id
                    }
                }
                terminal[state] = true
            }

            val fail = IntArray(next.size)
            val queue = ArrayDeque<Int>(next[0].values)
            while (queue.isNotEmpty()) {
                val state = queue.removeFirst()
                for ((ch, child) in next[state]) {
                    var link = fail[state]
                    while (link != 0 && ch !in next[link]) link = fail[link]
                    val target = next[link][ch] ?: 0
                    fail[child] = if (state == 0) 0 else target
                    terminal[child] = terminal[child] || terminal[fail[child]]
                    queue.add(child)
                }
            }

            fun step(startState: Int, ch: Char): Int {
                var cur = startState
                while (true) {
                    next[cur][ch]?.let { return it }
                    if (cur == 0) return 0
                    cur = fail[cur]
                }
            }

            val alphabet = patterns.joinToString("").toSet().sorted()
            val live = (0 until next.size).filter { !terminal[it] }
            val names = live.mapIndexed { idx, state ->
                state to if (idx == 0) prefix else "$prefix-$idx"
            }.toMap()

            return live.map { state ->
                val byTarget = mutableMapOf<Int, MutableSet<Char>>()
                val leavesRoot = mutableSetOf<Char>()
                for (ch in alphabet) {
                    val target = step(state, ch)
                    if (terminal[target]) {
                        leavesRoot += ch
                    } else if (target != 0) {
                        byTarget.getOrPut(target) { mutableSetOf() }.add(ch)
                        leavesRoot += ch
                    }
                }
                val alts = mutableListOf<Node>()
                alts += Node.Sequence(
                    listOf(
                        Node.Chars(leavesRoot, negated = true),
                        Node.Rule(names.getValue(0))
                    )
                )
                for ((target, chars) in byTarget.toSortedMap()) {
                    alts += Node.Sequence(
                        listOf(
                            Node.Chars(chars, negated = false),
                            Node.Rule(names.getValue(target))
                        )
                    )
                }
                alts += Node.Literal("")
                names.getValue(state) to Node.Choice(alts)
            }
        }

        private fun render(node: Node, top: Boolean): String = when (node) {
            is Node.Literal -> "\"${escapeLiteral(node.value)}\""
            is Node.Rule -> node.name
            is Node.Chars -> {
                val prefix = if (node.negated) "^" else ""
                val body = node.chars.sorted().joinToString("") { escapeCharClass(it) }
                "[$prefix$body]"
            }
            is Node.Sequence -> {
                val s = node.items.joinToString(" ") { render(it, top = false) }
                if (top || node.items.size <= 1) s else "($s)"
            }
            is Node.Choice -> {
                val s = node.items.joinToString(" | ") { render(it, top = false) }
                if (top || node.items.size <= 1) s else "($s)"
            }
            is Node.Repeated -> {
                val inner = render(node.item, top = false)
                when {
                    node.min == 0 && node.max == 1 -> "$inner?"
                    node.min == 0 && node.max == null -> "$inner*"
                    node.min == 1 && node.max == null -> "$inner+"
                    node.max != null -> "$inner{${node.min},${node.max}}"
                    else -> "$inner{${node.min},}"
                }
            }
        }

        private fun escapeLiteral(s: String): String = buildString {
            for (c in s) {
                when (c) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(c)
                }
            }
        }

        private fun escapeCharClass(c: Char): String = when (c) {
            '\\' -> "\\\\"
            ']' -> "\\]"
            '-' -> "\\-"
            '^' -> "\\^"
            '\n' -> "\\n"
            '\r' -> "\\r"
            '\t' -> "\\t"
            else -> c.toString()
        }
    }
}
