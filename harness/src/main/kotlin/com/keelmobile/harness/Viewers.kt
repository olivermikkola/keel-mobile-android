package com.keelmobile.harness

/**
 * Safe block parser for assistant replies: splits prose vs fenced code blocks
 * (preserving exact bytes for Copy), formats Markdown blocks, and ensures
 * model-written links are rendered as plain text (`label [url]`), never tap targets.
 */
sealed class MessageSegment {
    data class Prose(val blocks: List<ProseBlock>) : MessageSegment()
    data class CodeBlock(val language: String?, val code: String, val isClosed: Boolean) : MessageSegment()
}

sealed class ProseBlock {
    data class Heading(val level: Int, val text: String) : ProseBlock()
    data class Paragraph(val text: String) : ProseBlock()
    data class BulletItem(val text: String, val checked: Boolean? = null) : ProseBlock()
    data class NumberedItem(val number: Int, val text: String) : ProseBlock()
    data class BlockQuote(val text: String) : ProseBlock()
    data class Table(val headers: List<String>, val rows: List<List<String>>) : ProseBlock()
    object HorizontalRule : ProseBlock()
}

object ChatBlockParser {
    private val MD_LINK = Regex("""\[([^\]\n]+)\]\((https?://[^)\s]+)\)""")

    /** Neutralizes clickable links in model output: `[Open](https://x)` -> `Open [https://x]`. */
    fun defuseLinks(text: String): String = MD_LINK.replace(text) { m ->
        "${m.groupValues[1]} [${m.groupValues[2]}]"
    }

    fun parseSegments(text: String): List<MessageSegment> {
        val segments = mutableListOf<MessageSegment>()
        val proseBuf = StringBuilder()
        val codeBuf = StringBuilder()
        var inCode = false
        var codeLang: String? = null

        fun flushProse() {
            val raw = proseBuf.toString().trim()
            proseBuf.setLength(0)
            if (raw.isNotEmpty()) {
                segments += MessageSegment.Prose(parseProseBlocks(raw))
            }
        }

        // Preserve exact line endings inside code blocks
        val lines = splitPreservingNewlines(text)
        for (rawLine in lines) {
            val stripped = rawLine.trimEnd('\r', '\n')
            val trimmed = stripped.trim()
            if (!inCode) {
                if (trimmed.startsWith("```")) {
                    flushProse()
                    inCode = true
                    codeLang = trimmed.removePrefix("```").trim().takeIf { it.isNotEmpty() }
                    codeBuf.setLength(0)
                } else {
                    proseBuf.append(rawLine)
                }
            } else {
                if (trimmed == "```") {
                    val exactCode = codeBuf.toString().removeSuffix("\r\n").removeSuffix("\n")
                    segments += MessageSegment.CodeBlock(codeLang, exactCode, isClosed = true)
                    inCode = false
                    codeLang = null
                    codeBuf.setLength(0)
                } else {
                    codeBuf.append(rawLine)
                }
            }
        }
        if (inCode) {
            segments += MessageSegment.CodeBlock(codeLang, codeBuf.toString(), isClosed = false)
        } else {
            flushProse()
        }
        return segments
    }

    private fun splitPreservingNewlines(text: String): List<String> {
        val out = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < text.length) {
            if (text[i] == '\n') {
                out += text.substring(start, i + 1)
                start = i + 1
            }
            i++
        }
        if (start < text.length) out += text.substring(start)
        return out
    }

    fun parseProseBlocks(text: String): List<ProseBlock> {
        val lines = text.replace("\r\n", "\n").split("\n")
        val blocks = mutableListOf<ProseBlock>()
        val para = mutableListOf<String>()
        var i = 0

        fun flushPara() {
            if (para.isNotEmpty()) {
                blocks += ProseBlock.Paragraph(defuseLinks(para.joinToString("\n")))
                para.clear()
            }
        }

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> {
                    flushPara()
                    i++
                }
                Regex("^([-*_])\\1{2,}$").matches(trimmed) -> {
                    flushPara()
                    blocks += ProseBlock.HorizontalRule
                    i++
                }
                trimmed.startsWith("#") && Regex("^(#{1,6})\\s+(.+)$").matches(trimmed) -> {
                    flushPara()
                    val m = Regex("^(#{1,6})\\s+(.+)$").matchEntire(trimmed)!!
                    blocks += ProseBlock.Heading(m.groupValues[1].length, defuseLinks(m.groupValues[2]))
                    i++
                }
                trimmed.startsWith(">") -> {
                    flushPara()
                    blocks += ProseBlock.BlockQuote(defuseLinks(trimmed.removePrefix(">").trim()))
                    i++
                }
                Regex("^[-*+]\\s+\\[([ xX])]\\s+(.+)$").matches(trimmed) -> {
                    flushPara()
                    val m = Regex("^[-*+]\\s+\\[([ xX])]\\s+(.+)$").matchEntire(trimmed)!!
                    blocks += ProseBlock.BulletItem(defuseLinks(m.groupValues[2]), checked = m.groupValues[1] != " ")
                    i++
                }
                Regex("^[-*+]\\s+(.+)$").matches(trimmed) -> {
                    flushPara()
                    val m = Regex("^[-*+]\\s+(.+)$").matchEntire(trimmed)!!
                    blocks += ProseBlock.BulletItem(defuseLinks(m.groupValues[1]), checked = null)
                    i++
                }
                Regex("^(\\d{1,3})[.)]\\s+(.+)$").matches(trimmed) -> {
                    flushPara()
                    val m = Regex("^(\\d{1,3})[.)]\\s+(.+)$").matchEntire(trimmed)!!
                    blocks += ProseBlock.NumberedItem(m.groupValues[1].toInt(), defuseLinks(m.groupValues[2]))
                    i++
                }
                trimmed.contains("|") && i + 1 < lines.size && isTableSeparator(lines[i + 1].trim()) -> {
                    flushPara()
                    val headers = splitTableRow(trimmed).map(::defuseLinks)
                    i += 2
                    val rows = mutableListOf<List<String>>()
                    while (i < lines.size && lines[i].trim().contains("|")) {
                        rows += splitTableRow(lines[i].trim()).map(::defuseLinks)
                        i++
                    }
                    blocks += ProseBlock.Table(headers, rows)
                }
                else -> {
                    para += line
                    i++
                }
            }
        }
        flushPara()
        return blocks
    }

    private fun isTableSeparator(line: String): Boolean =
        line.contains("-") && Regex("""^\|?(\s*:?-{2,}:?\s*\|)+\s*:?-{2,}:?\s*\|?$""").matches(line)

    private fun splitTableRow(line: String): List<String> =
        line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }
}

/**
 * Flashcard & multiple-choice study deck parser (`Q:` / `A:` and `- [x]` choices).
 */
data class StudyDeck(
    val title: String?,
    val cards: List<Card>
) {
    data class Card(
        val question: String,
        val answer: String? = null,
        val choices: List<String> = emptyList(),
        val correct: List<Int> = emptyList()
    ) {
        val isQuiz: Boolean get() = choices.size >= 2 && correct.isNotEmpty()
    }

    companion object {
        const val MINIMUM_CARDS = 2

        private val LABEL_Q = Regex("""^(?:\d{1,3}[.)]\s*)?(?:\*\*)?(q|question)\s*\d{0,3}\s*[:.)](?:\*\*)?\s*(.*)$""", RegexOption.IGNORE_CASE)
        private val LABEL_A = Regex("""^(?:\d{1,3}[.)]\s*)?(?:\*\*)?(a|answer)\s*\d{0,3}\s*[:.)](?:\*\*)?\s*(.*)$""", RegexOption.IGNORE_CASE)
        private val CHOICE_REGEX = Regex("""^(?:[-*+]|\d{1,2}[.)]|[a-hA-H][.)])\s+(?:\[([ xX])]\s+)?(.+)$""")
        private val LETTER_PREFIX = Regex("""^(?:[a-h][.):]|\([a-h]\))\s+""")

        fun parse(text: String): StudyDeck {
            var title: String? = null
            val cards = mutableListOf<Card>()
            var curQ: String? = null
            var curA: String? = null
            val curChoices = mutableListOf<String>()
            val curCorrect = mutableListOf<Int>()
            var inAnswer = false

            fun finish() {
                val q = curQ?.trim().orEmpty()
                if (q.isNotEmpty()) {
                    val correct = if (curChoices.size >= 2 && curCorrect.isEmpty() && curA != null) {
                        matching(curA!!, curChoices)
                    } else curCorrect.toList()
                    val card = Card(q, curA?.trim(), curChoices.toList(), correct)
                    if (card.answer != null || card.isQuiz) cards += card
                }
                curQ = null
                curA = null
                curChoices.clear()
                curCorrect.clear()
                inAnswer = false
            }

            for (raw in text.replace("\r\n", "\n").split("\n")) {
                val line = raw.trim()
                val mq = LABEL_Q.matchEntire(line)
                val ma = if (curQ != null) LABEL_A.matchEntire(line) else null
                val mc = if (curQ != null && !inAnswer) CHOICE_REGEX.matchEntire(line) else null
                when {
                    mq != null -> {
                        finish()
                        curQ = mq.groupValues[2].replace("**", "").trim()
                    }
                    ma != null -> {
                        curA = ma.groupValues[2].replace("**", "").trim()
                        inAnswer = true
                    }
                    mc != null -> {
                        val mark = mc.groupValues[1]
                        if (mark.isNotEmpty() && mark != " ") curCorrect += curChoices.size
                        curChoices += mc.groupValues[2].trim()
                    }
                    line.startsWith("#") -> {
                        finish()
                        if (title == null && line.startsWith("# ")) title = line.removePrefix("# ").trim()
                    }
                    line.isEmpty() -> {
                        if (inAnswer) inAnswer = false
                    }
                    curQ != null -> {
                        if (inAnswer && curA != null) curA = "$curA\n$line"
                        else if (curChoices.isEmpty()) curQ = "$curQ\n$line"
                    }
                }
            }
            finish()
            return StudyDeck(title, cards)
        }

        fun matching(answer: String, choices: List<String>): List<Int> {
            fun plain(t: String): String =
                LETTER_PREFIX.replaceFirst(t.lowercase().trim(), "").trim(' ', '.', '*')
            val wanted = plain(answer)
            val exact = choices.indices.filter { plain(choices[it]) == wanted }
            if (exact.isNotEmpty()) return exact
            val named = choices.indices.filter { idx ->
                val c = plain(choices[idx])
                if (c.isEmpty() || !wanted.startsWith(c)) return@filter false
                val suffix = wanted.removePrefix(c).trim()
                suffix.startsWith("- ") || (suffix.firstOrNull()?.let { it in ",;:—–(" } ?: false)
            }
            return if (named.size == 1) named else emptyList()
        }
    }
}

/**
 * Self-contained offline HTML preview & viewer builder with strict no-network CSP.
 * Renders HTML websites/slides (inlining local workspace CSS/JS), Markdown documents,
 * CSV tables, and interactive Study flashcard/quiz decks.
 */
object FileView {
    const val OFFLINE_CSP =
        "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src data:; font-src data:;"

    private val LINK_CSS = Regex("""<link\b[^>]*rel=["']stylesheet["'][^>]*href=["']([^"']+)["'][^>]*/?>""", RegexOption.IGNORE_CASE)
    private val SCRIPT_SRC = Regex("""<script\b[^>]*src=["']([^"']+)["'][^>]*>\s*</script>""", RegexOption.IGNORE_CASE)

    fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    fun renderHtmlPreview(path: String, workspace: TaskWorkspace): String {
        val raw = workspace.readText(path) ?: return wrapDocument("Not found", "<p>File not found.</p>")
        val dir = if ('/' in path) path.substringBeforeLast('/') else ""
        fun resolveLocal(href: String): String? {
            if (href.startsWith("http://") || href.startsWith("https://") || href.startsWith("//")) return null
            val candidate = if (dir.isEmpty()) href else "$dir/$href"
            val norm = TaskWorkspace.normalizePath(candidate) ?: return null
            return workspace.readText(norm)
        }
        var inlined = LINK_CSS.replace(raw) { m ->
            val css = resolveLocal(m.groupValues[1])
            if (css != null) "<style>\n$css\n</style>" else ""
        }
        inlined = SCRIPT_SRC.replace(inlined) { m ->
            val js = resolveLocal(m.groupValues[1])
            if (js != null) "<script>\n$js\n</script>" else ""
        }
        val cspMeta = """<meta http-equiv="Content-Security-Policy" content="$OFFLINE_CSP">"""
        return if (inlined.contains("<head>", ignoreCase = true)) {
            inlined.replaceFirst(Regex("<head>", RegexOption.IGNORE_CASE), "<head>\n$cspMeta")
        } else {
            "$cspMeta\n$inlined"
        }
    }

    fun renderMarkdown(title: String, markdown: String): String {
        val deck = StudyDeck.parse(markdown)
        if (deck.cards.size >= StudyDeck.MINIMUM_CARDS) {
            return renderStudyDeck(deck.title ?: title, deck)
        }
        val segments = ChatBlockParser.parseSegments(markdown)
        val body = buildString {
            for (seg in segments) {
                when (seg) {
                    is MessageSegment.CodeBlock -> {
                        append("<pre><code>").append(escapeHtml(seg.code)).append("</code></pre>\n")
                    }
                    is MessageSegment.Prose -> {
                        for (b in seg.blocks) {
                            when (b) {
                                is ProseBlock.Heading -> append("<h${b.level}>${escapeHtml(b.text)}</h${b.level}>\n")
                                is ProseBlock.Paragraph -> append("<p>${escapeHtml(b.text).replace("\n", "<br>")}</p>\n")
                                is ProseBlock.BulletItem -> {
                                    val box = when (b.checked) {
                                        true -> "☑ "
                                        false -> "☐ "
                                        null -> "• "
                                    }
                                    append("<p class=\"li\">$box${escapeHtml(b.text)}</p>\n")
                                }
                                is ProseBlock.NumberedItem -> append("<p class=\"li\">${b.number}. ${escapeHtml(b.text)}</p>\n")
                                is ProseBlock.BlockQuote -> append("<blockquote>${escapeHtml(b.text)}</blockquote>\n")
                                is ProseBlock.HorizontalRule -> append("<hr>\n")
                                is ProseBlock.Table -> {
                                    append("<table><thead><tr>")
                                    b.headers.forEach { append("<th>${escapeHtml(it)}</th>") }
                                    append("</tr></thead><tbody>")
                                    b.rows.forEach { r ->
                                        append("<tr>")
                                        r.forEach { append("<td>${escapeHtml(it)}</td>") }
                                        append("</tr>")
                                    }
                                    append("</tbody></table>\n")
                                }
                            }
                        }
                    }
                }
            }
        }
        return wrapDocument(title, body)
    }

    fun renderCsv(title: String, csvText: String): String {
        val parsed = SqlSandbox.parseCsv(csvText)
        val body = buildString {
            append("<h1>${escapeHtml(title)}</h1>")
            append("<p class=\"meta\">${parsed.rows.size} rows · ${parsed.headers.size} columns</p>")
            append("<div class=\"scroll\"><table><thead><tr>")
            parsed.headers.forEach { append("<th>${escapeHtml(it)}</th>") }
            append("</tr></thead><tbody>")
            for (r in parsed.rows.take(1_000)) {
                append("<tr>")
                parsed.headers.indices.forEach { idx ->
                    append("<td>${escapeHtml(r.getOrNull(idx).orEmpty())}</td>")
                }
                append("</tr>")
            }
            append("</tbody></table></div>")
        }
        return wrapDocument(title, body)
    }

    fun renderStudyDeck(title: String, deck: StudyDeck): String {
        val body = buildString {
            append("<h1>${escapeHtml(title)}</h1>")
            append("<p class=\"meta\">${deck.cards.size} cards · tap to reveal</p>")
            deck.cards.forEachIndexed { idx, card ->
                append("<div class=\"card\">")
                append("<div class=\"q\"><strong>${idx + 1}.</strong> ${escapeHtml(card.question)}</div>")
                if (card.isQuiz) {
                    card.choices.forEachIndexed { cIdx, c ->
                        val isRight = cIdx in card.correct
                        append("""<button class="choice" onclick="this.className='choice ${if (isRight) "ok" else "no"}'">${escapeHtml(c)}</button>""")
                    }
                } else {
                    append("""<details><summary>Show answer</summary><div class="a">${escapeHtml(card.answer.orEmpty())}</div></details>""")
                }
                append("</div>\n")
            }
        }
        return wrapDocument(title, body)
    }

    private fun wrapDocument(title: String, body: String): String = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="$OFFLINE_CSP">
<title>${escapeHtml(title)}</title>
<style>
  :root { color-scheme: light dark; }
  body { font-family: system-ui, -apple-system, sans-serif; margin: 16px; line-height: 1.55; background: #f7f3e9; color: #2a241c; }
  @media (prefers-color-scheme: dark) {
    body { background: #0a1112; color: #eef4f2; }
    .card, pre, table { background: #101a1c; border-color: #21403b; }
    th { background: #162426; }
  }
  h1, h2, h3 { color: #1e705c; }
  @media (prefers-color-scheme: dark) { h1, h2, h3 { color: #76dfc2; } }
  pre { padding: 12px; border-radius: 12px; overflow-x: auto; border: 1px solid #d5cfc2; background: #fff; }
  .scroll { overflow-x: auto; }
  table { border-collapse: collapse; width: 100%; background: #fff; border: 1px solid #d5cfc2; }
  th, td { border: 1px solid #d5cfc2; padding: 8px 10px; text-align: left; }
  .card { border: 1px solid #d5cfc2; border-radius: 14px; padding: 14px; margin: 12px 0; background: #fff; }
  .choice { display: block; width: 100%; text-align: left; margin: 6px 0; padding: 10px 12px; border-radius: 10px; border: 1px solid #8ca3a0; background: transparent; color: inherit; font: inherit; }
  .choice.ok { border-color: #36b394; background: rgba(54,179,148,0.2); }
  .choice.no { border-color: #dc7648; background: rgba(220,118,72,0.2); }
  .meta { color: #7a7266; font-size: 0.9em; }
</style>
</head>
<body>
$body
</body>
</html>
""".trimIndent()
}
