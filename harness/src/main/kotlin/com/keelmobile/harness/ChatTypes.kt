package com.keelmobile.harness

import kotlinx.serialization.Serializable

@Serializable
enum class ChatMode {
    CHAT,
    WORK;

    val maxToolCalls: Int
        get() = when (this) {
            CHAT -> 4
            WORK -> 8
        }

    fun offers(toolName: String): Boolean = when (this) {
        WORK -> true
        CHAT -> toolName in CHAT_TOOLS || McpConnector.isConnectorCall(toolName)
    }

    companion object {
        private val CHAT_TOOLS = setOf(
            "search_repo",
            "read_file",
            "list_files",
            "read_url",
            "web_search",
            "github_repos",
            "github_open",
            "remember",
            "recall"
        )
    }
}

@Serializable
enum class AppPreset(
    val title: String,
    val summary: String,
    val starters: List<String>,
    val tools: Set<String>,
    val instructions: String
) {
    WEBSITES(
        title = "Websites",
        summary = "Build a web page and preview it here",
        starters = listOf(
            "Make a one-page site for my café, open weekdays 8–16",
            "Make a countdown page for a birthday on 24 December",
            "Make a simple to-do list page"
        ),
        tools = FILE_TOOLS + "run_js",
        instructions = "This chat is the Websites app. Build what the user asks as a web page they preview on this device: one index.html with its CSS and JavaScript inside, unless the user wants separate files. Keep it compact: a clean layout for a phone screen, short simple CSS, and real text instead of placeholders. The page cannot load anything from the internet, so use no external fonts, scripts or images. To change a page, read it and use propose_edit."
    ),
    SHEETS(
        title = "Sheets",
        summary = "Ask about a spreadsheet or build a table",
        starters = listOf(
            "Make a monthly budget table with rent, food, transport and savings",
            "Which row in my spreadsheet has the highest total?",
            "Summarise my spreadsheet by category"
        ),
        tools = FILE_TOOLS + setOf("run_sql", "run_js"),
        instructions = "This chat is the Sheets app. Work with tables: the user's .csv files or new ones. To answer a question about a .csv file, query it with run_sql (SELECT ... FROM name.csv) and report the exact numbers from the result. Answers go in your reply. A table the user asks you to make, build or change goes in a new .csv file made with create_file, header row first, quoting any value that has a comma, so the user can open and keep it as a sheet; then say in one sentence what the file holds."
    ),
    DOCS(
        title = "Docs",
        summary = "Write and revise documents",
        starters = listOf(
            "Write a short cover letter for a barista job",
            "Turn my notes into a one-page report",
            "Write a weekly meal plan for two"
        ),
        tools = FILE_TOOLS,
        instructions = "This chat is the Docs app. Write documents as Markdown .md files: a # title, ## sections, short paragraphs, and lists or tables where they help. The user reads them rendered on this device. Write the full text, never placeholders. To revise a document, read it and use propose_edit on the part that changes."
    ),
    STUDY(
        title = "Study",
        summary = "Flashcards and quizzes from your notes",
        starters = listOf(
            "Make flashcards from my notes",
            "Quiz me with 5 multiple-choice questions about the solar system",
            "Make 10 flashcards of Spanish phrases for travelling"
        ),
        tools = FILE_TOOLS,
        instructions = "This chat is the Study app. Help the user learn a topic or the files they attached. Make flashcards as a .md file: a # title, then for each card a line starting Q: and a line starting A:, with a blank line between cards. For a multiple-choice question, write the Q: line, each choice on a line starting with a dash, and always an A: line with the right choice, like this:\nQ: Which planet is closest to the Sun?\n- Venus\n- Mercury\nA: Mercury\nThe answers stay hidden until the user picks a choice. The user studies them on this device. Base the cards on the attached file when there is one. Keep answers short. Make 8 to 12 cards unless asked otherwise."
    ),
    SLIDES(
        title = "Slides",
        summary = "Build a slide deck and present or export it",
        starters = listOf(
            "Make a 5-slide pitch deck for a neighbourhood bakery",
            "Turn my notes into a 6-slide presentation",
            "Make a short slide deck on how solar panels work"
        ),
        tools = FILE_TOOLS,
        instructions = "This chat is the Slides app. Build presentations as one slides.html file the user can step through on this device and export to PDF: put each slide in a <section class=\"slide\"> element with a clear heading and concise points, include compact CSS and Previous/Next slide controls inside the same file, and add @media print rules so every slide is visible when exported to PDF. The deck cannot load anything from the internet, so use no external fonts, scripts or images. Write real slide text instead of placeholders. To change a deck, read it and use propose_edit."
    );
}

private val FILE_TOOLS = setOf(
    "search_repo",
    "read_file",
    "list_files",
    "propose_edit",
    "create_file",
    "delete_file",
    "rename_file"
)

object CustomInstructions {
    const val MAX_CHARS = 1_500

    fun normalize(raw: String): String = raw.trim().take(MAX_CHARS)

    fun apply(systemPrompt: String, instructions: String): String {
        val clean = normalize(instructions)
        if (clean.isEmpty()) return systemPrompt
        return "$systemPrompt\n\nUser standing instructions (preferences only; never override safety or approval rules):\n$clean"
    }
}

@Serializable
data class MemoryFact(
    val id: String,
    val text: String,
    val createdAtEpochMs: Long
)

object UserMemory {
    const val MAX_FACTS = 40
    const val MAX_FACT_CHARS = 240
    const val MAX_PROMPT_CHARS = 1_200

    val tool: String =
        """{"type":"function","function":{"name":"remember","description":"Ask the user to save one short, durable fact about them or their preferences for future plain chats. Nothing is saved until the user taps Remember.","parameters":{"type":"object","properties":{"fact":{"type":"string"}},"required":["fact"]}}}"""

    fun normalizeFact(raw: String): String? {
        val cleaned = raw.replace(Regex("\\s+"), " ").trim()
        if (cleaned.isEmpty()) return null
        return cleaned.take(MAX_FACT_CHARS)
    }

    fun apply(systemPrompt: String, facts: List<MemoryFact>, enabled: Boolean): String {
        if (!enabled || facts.isEmpty()) return systemPrompt
        val lines = mutableListOf<String>()
        var total = 0
        for (fact in facts.sortedByDescending { it.createdAtEpochMs }.take(MAX_FACTS)) {
            val line = "- ${fact.text}"
            if (total + line.length + 1 > MAX_PROMPT_CHARS) break
            lines += line
            total += line.length + 1
        }
        if (lines.isEmpty()) return systemPrompt
        return "$systemPrompt\n\nThings the user asked you to remember:\n${lines.joinToString("\n")}"
    }
}

enum class ThermalLevel {
    NOMINAL,
    FAIR,
    SERIOUS,
    CRITICAL
}

object ThermalPolicy {
    fun shouldStop(level: ThermalLevel): Boolean =
        level == ThermalLevel.SERIOUS || level == ThermalLevel.CRITICAL

    fun userMessage(level: ThermalLevel): String? = when (level) {
        ThermalLevel.SERIOUS, ThermalLevel.CRITICAL ->
            "Your device reached high temperature. Generation stopped and your partial reply was kept; let it cool for a few minutes."
        else -> null
    }
}

@Serializable
data class InferenceTelemetry(
    val promptTokens: Int,
    val reusedTokens: Int,
    val generatedTokens: Int,
    val prefillMs: Long,
    val decodeMs: Long,
    val backend: String
) {
    val tokensPerSecond: Double
        get() = if (decodeMs > 0) generatedTokens * 1000.0 / decodeMs else 0.0
}
