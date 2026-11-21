package com.keelmobile.harness

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.ceil
import kotlin.math.max

/**
 * Port of desktop Keel's repeat-tool reminder (thresholds 2, 4, 7) plus
 * overlapping read_file range detection.
 */
class RepeatGuard(thresholds: List<Int> = DESKTOP_THRESHOLDS) {
    val thresholds: List<Int> = thresholds.filter { it > 0 }.toSet().sorted()
    private var lastKey: String = ""
    var count: Int = 0
        private set
    private val readRangesByPath = mutableMapOf<String, MutableList<IntRange>>()

    fun reset() {
        lastKey = ""
        count = 0
        readRangesByPath.clear()
    }

    fun observe(name: String, arguments: Map<String, String>): String? {
        invalidateReadsIfMutated(name, arguments)
        val canonical = canonical(arguments)
        val key = "$name:$canonical"
        val isConsecutiveRepeat = key == lastKey
        if (isConsecutiveRepeat) count++ else {
            lastKey = key
            count = 1
        }

        if (count in thresholds) {
            recordReadRangeIfNeeded(name, arguments)
            return if (count == thresholds.firstOrNull()) GENTLE
            else detailed(name, count, canonical)
        }

        if (!isConsecutiveRepeat && name == "read_file") {
            val pair = readRange(arguments)
            if (pair != null) {
                val (path, range) = pair
                val previous = readRangesByPath.getOrPut(path) { mutableListOf() }
                val overlapped = if (range in previous) null else previous.firstOrNull { overlaps(it, range) }
                if (range !in previous) previous.add(range)
                if (overlapped != null) return overlappingRead(path, range, overlapped)
            }
        } else {
            recordReadRangeIfNeeded(name, arguments)
        }
        return null
    }

    private fun invalidateReadsIfMutated(name: String, arguments: Map<String, String>) {
        if (name !in setOf("propose_edit", "create_file", "delete_file", "rename_file")) return
        arguments["path"]?.let { readRangesByPath.remove(it) }
        arguments["destination_path"]?.let { readRangesByPath.remove(it) }
    }

    private fun recordReadRangeIfNeeded(name: String, arguments: Map<String, String>) {
        if (name != "read_file") return
        val (path, range) = readRange(arguments) ?: return
        val list = readRangesByPath.getOrPut(path) { mutableListOf() }
        if (range !in list) list.add(range)
    }

    companion object {
        val DESKTOP_THRESHOLDS = listOf(2, 4, 7)
        private const val PREVIEW_LIMIT = 500

        const val GENTLE =
            "\n\n[repeat-tool-reminder] You are repeating the exact same tool call with identical arguments. Carefully analyze the previous result before calling again: if the task is not complete, try a different approach or different arguments instead of repeating the call."

        fun canonical(arguments: Map<String, String>): String {
            val sorted = arguments.toSortedMap().mapValues { JsonPrimitive(it.value) }
            return JsonObject(sorted).toString()
        }

        private fun readRange(arguments: Map<String, String>): Pair<String, IntRange>? {
            val path = arguments["path"]?.takeIf { it.isNotEmpty() } ?: return null
            val start = when {
                arguments["start_line"]?.toIntOrNull() != null -> max(1, arguments["start_line"]!!.toInt())
                arguments["offset"]?.toIntOrNull() != null -> max(1, arguments["offset"]!!.toInt() + 1)
                else -> 1
            }
            val lineCount = max(1, arguments["count"]?.toIntOrNull() ?: 1)
            return path to (start..(start + lineCount - 1))
        }

        private fun overlaps(a: IntRange, b: IntRange): Boolean =
            a.first <= b.last && b.first <= a.last

        fun overlappingRead(path: String, requested: IntRange, previous: IntRange): String =
            "\n\n[repeat-tool-reminder] You already read lines ${previous.first}–${previous.last} of $path in this turn (overlaps requested ${requested.first}–${requested.last}). Inspect that earlier result or read a non-overlapping range."

        fun detailed(name: String, count: Int, canonical: String): String {
            val preview = if (canonical.length > PREVIEW_LIMIT) {
                canonical.take(PREVIEW_LIMIT) + "… (+${canonical.length - PREVIEW_LIMIT} more chars)"
            } else canonical
            return "\n\n[repeat-tool-reminder] Repeated tool call detected:\n- tool: $name\n- consecutive_calls: $count\n- arguments: $preview\nThe repeated calls are not making progress. Do not call this tool with these exact arguments again. Inspect the latest result and choose a different action, different arguments, or finish the task if enough evidence has been gathered."
        }
    }
}

/**
 * Environment circuit breaker: trips after 3 consecutive failures.
 */
class CircuitBreaker(tripThreshold: Int = 3) {
    sealed class Failure(val advice: String) {
        object RateLimited : Failure("GitHub's rate limit is reached. Retrying will not help; tell the user and stop.")
        object NotFound : Failure("The paths you are trying do not exist at this commit. List the parent folder once, then choose from what exists.")
        object Unauthorized : Failure("Access was refused. A credential problem cannot be fixed by the model; tell the user and stop.")
        object Network : Failure("The network is failing. Tell the user and stop instead of retrying.")
        data class Other(val detail: String) : Failure("The same kind of failure keeps happening ($detail). Change approach or stop and explain.")
    }

    val tripThreshold: Int = max(1, tripThreshold)
    var consecutiveFailures: Int = 0
        private set

    fun recordSuccess() {
        consecutiveFailures = 0
    }

    fun recordFailure(failure: Failure): String? {
        consecutiveFailures++
        if (consecutiveFailures < tripThreshold) return null
        return "\n\n[circuit-breaker] $consecutiveFailures tool calls in a row failed. ${failure.advice}"
    }
}

/**
 * Detects degenerate looping generation in small models (e.g., repeating the same
 * phrase/sentence over and over) so streaming can stop early cleanly.
 */
class RepetitionGuard(
    private val minUnitChars: Int = 24,
    private val maxUnitChars: Int = 160,
    private val requiredRepeats: Int = 4
) {
    private val buffer = StringBuilder()

    fun appendAndCheckLoop(chunk: String): Boolean {
        buffer.append(chunk)
        val total = buffer.length
        if (total < minUnitChars * requiredRepeats) return false
        val maxUnit = minOf(maxUnitChars, total / requiredRepeats)
        for (unit in minUnitChars..maxUnit) {
            val tail = buffer.substring(total - unit, total)
            if (tail.isBlank()) continue
            var matched = 1
            var pos = total - unit * 2
            while (pos >= 0 && matched < requiredRepeats) {
                if (buffer.substring(pos, pos + unit) == tail) {
                    matched++
                    pos -= unit
                } else {
                    break
                }
            }
            if (matched >= requiredRepeats) return true
        }
        return false
    }

    fun reset() {
        buffer.setLength(0)
    }
}

data class HarnessMessage(
    val role: Role,
    var text: String
) {
    enum class Role { USER, ASSISTANT, TOOL, SUMMARY }
}

data class ToolResultPruner(
    val thresholdChars: Int,
    val headChars: Int,
    val tailChars: Int
) {
    fun prune(text: String): String {
        val codePoints = text.codePointCount(0, text.length)
        if (codePoints <= thresholdChars) return text
        val headEnd = text.offsetByCodePoints(0, minOf(headChars, codePoints))
        val tailStart = text.offsetByCodePoints(text.length, -minOf(tailChars, codePoints))
        val head = text.substring(0, headEnd)
        val tail = text.substring(tailStart)
        val omitted = max(0, codePoints - headChars - tailChars)
        return "$head\n…[$omitted chars middle-pruned by keel compaction]…\n$tail"
    }

    companion object {
        val DESKTOP = ToolResultPruner(thresholdChars = 8192, headChars = 4096, tailChars = 1024)
    }
}

/**
 * Deterministic context budget & compaction (desktop ratios: compact at 75%,
 * aim 50% or 30% for stablePrefix, keep 3 recent steps).
 */
class ContextBudget(
    val maxContextTokens: Int,
    val style: CompactionStyle = CompactionStyle.STABLE_PREFIX
) {
    enum class CompactionStyle { EAGER, STABLE_PREFIX }

    val compactionThresholdTokens: Int = (maxContextTokens * 0.75).toInt()
    val targetTokens: Int = (maxContextTokens * 0.50).toInt()
    val lowWaterTokens: Int = (maxContextTokens * 0.30).toInt()
    val keepRecentSteps: Int = 3
    val charsPerToken: Double = 3.8
    val messageOverheadTokens: Int = 4
    val oldResultPruner: ToolResultPruner = ToolResultPruner(thresholdChars = 1200, headChars = 800, tailChars = 200)

    data class Report(
        val beforeTokens: Int,
        val afterTokens: Int,
        val prunedResults: Int,
        val summarizedMessages: Int
    )

    fun estimate(message: HarnessMessage): Int {
        val cp = message.text.codePointCount(0, message.text.length)
        return ceil(cp / charsPerToken).toInt() + messageOverheadTokens
    }

    fun estimate(messages: List<HarnessMessage>): Int = messages.sumOf { estimate(it) }

    fun shouldCompact(messages: List<HarnessMessage>, reservedTokens: Int = 0): Boolean =
        estimate(messages) + reservedTokens > compactionThresholdTokens

    fun compact(messages: MutableList<HarnessMessage>, reservedTokens: Int = 0): Report? {
        val before = estimate(messages)
        if (before + reservedTokens <= compactionThresholdTokens) return null
        val goal = if (style == CompactionStyle.STABLE_PREFIX) lowWaterTokens else targetTokens
        val target = max(0, goal - reservedTokens)
        val taskIndex = messages.indexOfFirst { it.role == HarnessMessage.Role.USER }
        if (taskIndex < 0) return null

        val assistantIndices = messages.indices.filter {
            messages[it].role == HarnessMessage.Role.ASSISTANT && it > taskIndex
        }
        val tailStart = when {
            assistantIndices.isEmpty() -> messages.size
            assistantIndices.size > keepRecentSteps -> assistantIndices[assistantIndices.size - keepRecentSteps]
            else -> assistantIndices.first()
        }
        val middleStart = taskIndex + 1
        val middleEnd = max(middleStart, tailStart)

        var pruned = 0
        if (style == CompactionStyle.EAGER) {
            for (i in middleStart until middleEnd) {
                if (messages[i].role == HarnessMessage.Role.TOOL) {
                    val shorter = oldResultPruner.prune(messages[i].text)
                    if (shorter != messages[i].text) {
                        messages[i].text = shorter
                        pruned++
                    }
                }
            }
        }

        var summarized = 0
        if (estimate(messages) > target && middleStart < middleEnd) {
            var remaining = estimate(messages)
            val foldIndices = mutableListOf<Int>()
            var i = middleStart
            while (i < middleEnd && remaining > target) {
                val isCall = messages[i].role == HarnessMessage.Role.ASSISTANT && callLine(messages[i].text) != null
                if (!isCall && messages[i].role != HarnessMessage.Role.SUMMARY) {
                    i++
                    continue
                }
                var j = i + 1
                if (isCall) {
                    while (j < middleEnd && messages[j].role == HarnessMessage.Role.TOOL) j++
                }
                for (k in i until j) {
                    foldIndices += k
                    remaining -= estimate(messages[k])
                }
                i = j
            }
            if (foldIndices.isNotEmpty()) {
                val first = foldIndices.first()
                val folded = foldIndices.map { messages[it] }
                val note = HarnessMessage(HarnessMessage.Role.SUMMARY, summarize(folded))
                val skip = foldIndices.toSet()
                val rebuilt = mutableListOf<HarnessMessage>()
                for (idx in messages.indices) {
                    if (idx == first) rebuilt += note
                    if (idx !in skip) rebuilt += messages[idx]
                }
                messages.clear()
                messages.addAll(rebuilt)
                summarized = folded.size
            }
        }

        if (estimate(messages) > target) {
            val newestTool = messages.indexOfLast { it.role == HarnessMessage.Role.TOOL }
            if (newestTool >= 0) {
                for (idx in messages.indices) {
                    if (messages[idx].role == HarnessMessage.Role.TOOL) {
                        val pruner = if (idx == newestTool) ToolResultPruner.DESKTOP else oldResultPruner
                        val shorter = pruner.prune(messages[idx].text)
                        if (shorter != messages[idx].text) {
                            messages[idx].text = shorter
                            pruned++
                        }
                    }
                }
            }
        }

        return Report(
            beforeTokens = before,
            afterTokens = estimate(messages),
            prunedResults = pruned,
            summarizedMessages = summarized
        )
    }

    companion object {
        private fun callLine(text: String): String? {
            val call = runCatching { DirectorCall.parse(ToolCallText.normalize(text).text) }.getOrNull()
                ?: return null
            return "- ${call.label}"
        }

        private fun summarize(folded: List<HarnessMessage>): String {
            val lines = mutableListOf<String>()
            for (msg in folded) {
                when (msg.role) {
                    HarnessMessage.Role.ASSISTANT -> callLine(msg.text)?.let { lines += it }
                    HarnessMessage.Role.SUMMARY -> {
                        msg.text.lines().filter { it.startsWith("- ") }.forEach { lines += it }
                    }
                    else -> Unit
                }
            }
            return if (lines.isEmpty()) "[Earlier steps compacted to stay within context window.]"
            else "[Earlier steps compacted:\n${lines.joinToString("\n")}\n]"
        }
    }
}
