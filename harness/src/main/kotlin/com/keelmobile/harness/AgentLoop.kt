package com.keelmobile.harness

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cancellation token shared between UI Stop button, lifecycle pressure listeners,
 * RepetitionGuard, and native llama.cpp token loop.
 */
class InferenceCancellation(private val parent: InferenceCancellation? = null) {
    private val cancelled = AtomicBoolean(false)

    fun cancel() {
        cancelled.set(true)
    }

    val isCancelled: Boolean
        get() = cancelled.get() || (parent?.isCancelled == true)
}

data class LocalGenerationRequest(
    val model: LocalModel,
    val modelPath: String,
    val prompt: String,
    val maxTokens: Int,
    val grammar: ToolCallGrammar? = null,
    val greedy: Boolean = false,
    val projectorPath: String? = null,
    val images: List<VisionImage> = emptyList()
)

/**
 * On-device inference backend contract. Implemented by native llama.cpp JNI in :app
 * and by deterministic fake engines in unit tests.
 */
interface LocalInferenceEngine {
    suspend fun generate(
        request: LocalGenerationRequest,
        cancellation: InferenceCancellation,
        onToken: (String) -> Unit
    ): InferenceTelemetry
}

/**
 * Explicit user approval request emitted by the agent loop when a tool needs
 * consent before changing state or leaving the device.
 */
sealed class PendingApproval {
    abstract val id: String

    data class FileChange(
        override val id: String,
        val edit: ProposedEdit
    ) : PendingApproval()

    data class WebSearchQuery(
        override val id: String,
        val query: String
    ) : PendingApproval()

    data class RememberFact(
        override val id: String,
        val fact: String
    ) : PendingApproval()

    data class McpWriteCall(
        override val id: String,
        val toolName: String,
        val arguments: Map<String, String>
    ) : PendingApproval()
}

data class AgentStepEvent(
    val toolName: String,
    val label: String,
    val resultSummary: String,
    val isError: Boolean = false
)

/**
 * External host hooks for optional tools (read_url, web_search, GitHub, MCP, recall).
 * All are fail-closed by default when unconfigured.
 */
interface ToolHost {
    suspend fun readUrl(url: String): String =
        throw IllegalStateException("Reading web pages is unavailable.")

    suspend fun searchWeb(query: String): List<WebSearch.ResultItem> =
        throw IllegalStateException("Web search is not configured. Add a Brave Search API key in Connections.")

    suspend fun requestWebSearchApproval(query: String): Boolean = false

    suspend fun requestMemoryApproval(fact: String): Boolean = false

    suspend fun listGitHubRepos(limit: Int): String =
        throw IllegalStateException("GitHub is not connected.")

    suspend fun openGitHubRepo(repository: String): String =
        throw IllegalStateException("GitHub is not connected.")

    suspend fun recall(query: String): String =
        "Search by meaning is not installed. Download it from Models to enable recall."

    suspend fun callMcpTool(name: String, arguments: Map<String, String>): String =
        throw IllegalStateException("MCP connector tool $name is not connected.")
}

data class AgentRunResult(
    val finalReply: String,
    val steps: List<AgentStepEvent>,
    val proposedEdits: List<ProposedEdit>,
    val stoppedByUser: Boolean = false,
    val lastTelemetry: InferenceTelemetry? = null
)

/**
 * Full multi-step on-device agent loop combining:
 * - Lazy GBNF tool-call grammar generation
 * - Native & Qwen tolerant tool-call normalization + strict DirectorCall parsing
 * - RepeatGuard (thresholds 2, 4, 7 + overlapping reads)
 * - CircuitBreaker (trips after 3 consecutive failures)
 * - RepetitionGuard (stops stuck looping token streams early)
 * - ContextBudget compaction (stable-prefix by default)
 * - Explicit approval gates: model output NEVER directly mutates workspace files
 */
class AgentLoop(
    private val engine: LocalInferenceEngine,
    private val workspace: TaskWorkspace,
    private val sqlSandbox: SqlSandbox,
    private val host: ToolHost = object : ToolHost {}
) {
    suspend fun runTurn(
        model: LocalModel,
        modelPath: String,
        systemPrompt: String,
        history: MutableList<HarnessMessage>,
        mode: ChatMode,
        thinking: Boolean,
        allowedUrls: MutableSet<String>,
        cancellation: InferenceCancellation,
        projectorPath: String? = null,
        pendingImages: List<VisionImage> = emptyList(),
        onStreamText: (String) -> Unit = {},
        onStep: (AgentStepEvent) -> Unit = {},
        onProposal: (ProposedEdit) -> Unit = {}
    ): AgentRunResult {
        val repeatGuard = RepeatGuard()
        val circuitBreaker = CircuitBreaker()
        val budget = ContextBudget(model.contextSize, ContextBudget.CompactionStyle.STABLE_PREFIX)
        val steps = mutableListOf<AgentStepEvent>()
        val proposals = mutableListOf<ProposedEdit>()
        var lastTelemetry: InferenceTelemetry? = null
        val maxCalls = mode.maxToolCalls
        var callsMade = 0

        val reservedTokens = budget.estimate(HarnessMessage(HarnessMessage.Role.USER, systemPrompt)) +
            AgentPrompt.replyTokenLimit(writing = mode == ChatMode.WORK, thinking = thinking)

        while (!cancellation.isCancelled) {
            budget.compact(history, reservedTokens = reservedTokens)

            val promptMessages = history.map { msg ->
                val role = when (msg.role) {
                    HarnessMessage.Role.USER, HarnessMessage.Role.SUMMARY -> "user"
                    HarnessMessage.Role.ASSISTANT -> "assistant"
                    HarnessMessage.Role.TOOL -> "tool"
                }
                PromptMessage(role, msg.text)
            }
            val lastUserIdx = promptMessages.indexOfLast { it.role == "user" }
            val imageCounts = if (pendingImages.isNotEmpty() && lastUserIdx >= 0) {
                List(promptMessages.size) { idx -> if (idx == lastUserIdx) pendingImages.size else 0 }
            } else {
                emptyList()
            }
            val renderedPrompt = ModelPrompt.render(
                family = model.family,
                system = systemPrompt,
                messages = promptMessages,
                imageCounts = imageCounts,
                thinking = thinking
            )
            val grammar = ToolCallGrammar.makeFromPrompt(renderedPrompt, model.family)
            val maxTokens = AgentPrompt.replyTokenLimit(writing = mode == ChatMode.WORK, thinking = thinking)

            val stepCancel = InferenceCancellation(parent = cancellation)
            val repGuard = RepetitionGuard()
            val rawBuilder = StringBuilder()

            lastTelemetry = engine.generate(
                request = LocalGenerationRequest(
                    model = model,
                    modelPath = modelPath,
                    prompt = renderedPrompt,
                    maxTokens = maxTokens,
                    grammar = grammar,
                    projectorPath = projectorPath,
                    images = pendingImages
                ),
                cancellation = stepCancel
            ) { token ->
                rawBuilder.append(token)
                val visible = ToolCallText.visibleReply(rawBuilder.toString())
                if (!visible.contains("<tool_call>") && !visible.contains("<function")) {
                    onStreamText(visible)
                }
                if (repGuard.appendAndCheckLoop(token)) {
                    stepCancel.cancel()
                }
            }

            val rawOutput = rawBuilder.toString()
            if (cancellation.isCancelled) {
                val (clean, _) = ToolCallText.cleanReply(rawOutput)
                return AgentRunResult(clean, steps, proposals, stoppedByUser = true, lastTelemetry = lastTelemetry)
            }

            // Try parsing a tool call
            val parsedCall: DirectorCall? = try {
                val nativeNorm = NativeToolCall.normalize(rawOutput, model.family)
                val textNorm = ToolCallText.normalize(nativeNorm)
                DirectorCall.parse(textNorm.text)
            } catch (e: DirectorToolError) {
                val errText = e.message ?: "Malformed tool call."
                val cb = circuitBreaker.recordFailure(CircuitBreaker.Failure.Other(errText))
                history += HarnessMessage(HarnessMessage.Role.ASSISTANT, rawOutput)
                history += HarnessMessage(HarnessMessage.Role.TOOL, errText + (cb ?: ""))
                steps += AgentStepEvent("invalid_call", "Malformed tool call", errText, isError = true)
                onStep(steps.last())
                callsMade++
                if (callsMade >= maxCalls) {
                    val (clean, _) = ToolCallText.cleanReply(rawOutput)
                    return AgentRunResult(clean, steps, proposals, lastTelemetry = lastTelemetry)
                }
                continue
            }

            if (parsedCall == null) {
                val (cleanReply, _) = ToolCallText.cleanReply(rawOutput)
                history += HarnessMessage(HarnessMessage.Role.ASSISTANT, cleanReply)
                return AgentRunResult(cleanReply, steps, proposals, lastTelemetry = lastTelemetry)
            }

            if (callsMade >= maxCalls) {
                val stopNote = "Reached the limit of $maxCalls tool steps for this turn. Here is what I have so far."
                history += HarnessMessage(HarnessMessage.Role.ASSISTANT, stopNote)
                return AgentRunResult(stopNote, steps, proposals, lastTelemetry = lastTelemetry)
            }
            callsMade++

            val repeatReminder = repeatGuard.observe(parsedCall.name, parsedCall.arguments)
            val exec = executeTool(parsedCall, mode, allowedUrls, proposals, onProposal)
            val resultBody = if (exec.isError) {
                val cb = circuitBreaker.recordFailure(exec.failureKind ?: CircuitBreaker.Failure.Other(exec.output))
                exec.output + (repeatReminder ?: "") + (cb ?: "")
            } else {
                circuitBreaker.recordSuccess()
                exec.output + (repeatReminder ?: "")
            }

            val recordedCall = if (model.family == ModelFamily.MINI_CPM5) {
                NativeToolCall.canonicalCall(parsedCall.name, parsedCall.arguments)
            } else {
                rawOutput.trim()
            }
            history += HarnessMessage(HarnessMessage.Role.ASSISTANT, recordedCall)
            history += HarnessMessage(HarnessMessage.Role.TOOL, resultBody)

            val event = AgentStepEvent(
                toolName = parsedCall.name,
                label = parsedCall.label,
                resultSummary = resultBody.lines().firstOrNull()?.take(160).orEmpty(),
                isError = exec.isError
            )
            steps += event
            onStep(event)
        }

        return AgentRunResult("", steps, proposals, stoppedByUser = true, lastTelemetry = lastTelemetry)
    }

    private data class ToolExecOutcome(
        val output: String,
        val isError: Boolean = false,
        val failureKind: CircuitBreaker.Failure? = null
    )

    private suspend fun executeTool(
        call: DirectorCall,
        mode: ChatMode,
        allowedUrls: MutableSet<String>,
        proposals: MutableList<ProposedEdit>,
        onProposal: (ProposedEdit) -> Unit
    ): ToolExecOutcome {
        return try {
            when (call.name) {
                "list_files" -> {
                    val path = call.arguments["path"] ?: "."
                    val offset = call.arguments["offset"]?.toIntOrNull() ?: 0
                    val count = call.arguments["count"]?.toIntOrNull() ?: 60
                    val items = workspace.listFolder(path, offset, count)
                    val out = if (items.isEmpty()) "(empty folder)" else items.joinToString("\n")
                    ToolExecOutcome(out)
                }
                "read_file" -> {
                    val path = call.arguments["path"] ?: throw IllegalArgumentException("path is required")
                    val start = call.arguments["start_line"]?.toIntOrNull() ?: 1
                    val count = call.arguments["count"]?.toIntOrNull() ?: 200
                    val res = workspace.readFileLines(path, start, count)
                        ?: return ToolExecOutcome("File not found: $path", isError = true, failureKind = CircuitBreaker.Failure.NotFound)
                    val footer = if (res.nextStartLine != null) "\n[next_start_line: ${res.nextStartLine} of ${res.totalLines}]" else ""
                    ToolExecOutcome("${res.content}$footer")
                }
                "search_repo" -> {
                    val query = call.arguments["query"] ?: throw IllegalArgumentException("query is required")
                    val path = call.arguments["path"]
                    val count = call.arguments["count"]?.toIntOrNull() ?: 20
                    val offset = call.arguments["offset"]?.toIntOrNull() ?: 0
                    val res = workspace.search(query, path, count, offset)
                    val header = "Search (${res.mode}, ${res.totalMatches} match(es), ${res.skippedBinaryFiles} binary skipped):"
                    val body = if (res.matches.isEmpty()) "(no matches)"
                    else res.matches.joinToString("\n") { "${it.path}:${it.line}: ${it.text}" }
                    ToolExecOutcome("$header\n$body")
                }
                "propose_edit", "stage_file_edit" -> {
                    if (mode == ChatMode.CHAT) throw DirectorToolError.ApprovalRequired
                    val path = call.arguments["path"] ?: throw IllegalArgumentException("path is required")
                    val oldText = call.arguments["old_text"] ?: throw IllegalArgumentException("old_text is required")
                    val newText = call.arguments["new_text"] ?: throw IllegalArgumentException("new_text is required")
                    val edit = try {
                        workspace.planEdit(UUID.randomUUID().toString(), EditOperation.REPLACE, path, oldText, newText)
                    } catch (e: IllegalArgumentException) {
                        val rawOld = call.rawArguments["old_text"]
                        val rawNew = call.rawArguments["new_text"]
                        if (rawOld != null && rawNew != null && rawOld != oldText) {
                            workspace.planEdit(UUID.randomUUID().toString(), EditOperation.REPLACE, path, rawOld, rawNew)
                        } else throw e
                    }
                    proposals += edit
                    onProposal(edit)
                    ToolExecOutcome("Proposed edit to ${edit.path} for user review. Nothing changed yet until the user taps Apply.")
                }
                "create_file" -> {
                    if (mode == ChatMode.CHAT) throw DirectorToolError.ApprovalRequired
                    val path = call.arguments["path"] ?: throw IllegalArgumentException("path is required")
                    val newText = call.arguments["new_text"] ?: ""
                    val edit = workspace.planEdit(UUID.randomUUID().toString(), EditOperation.CREATE, path, newText = newText)
                    proposals += edit
                    onProposal(edit)
                    ToolExecOutcome("Proposed new file ${edit.path} for user review. Nothing created yet until the user taps Apply.")
                }
                "delete_file" -> {
                    if (mode == ChatMode.CHAT) throw DirectorToolError.ApprovalRequired
                    val path = call.arguments["path"] ?: throw IllegalArgumentException("path is required")
                    val edit = workspace.planEdit(UUID.randomUUID().toString(), EditOperation.DELETE, path)
                    proposals += edit
                    onProposal(edit)
                    ToolExecOutcome("Proposed deleting ${edit.path} for user review. Nothing deleted yet until the user taps Apply.")
                }
                "rename_file" -> {
                    if (mode == ChatMode.CHAT) throw DirectorToolError.ApprovalRequired
                    val path = call.arguments["path"] ?: throw IllegalArgumentException("path is required")
                    val dest = call.arguments["destination_path"] ?: throw IllegalArgumentException("destination_path is required")
                    val edit = workspace.planEdit(UUID.randomUUID().toString(), EditOperation.RENAME, path, destinationPath = dest)
                    proposals += edit
                    onProposal(edit)
                    ToolExecOutcome("Proposed renaming ${edit.path} -> ${edit.destinationPath} for user review.")
                }
                "run_sql" -> {
                    if (mode == ChatMode.CHAT) throw DirectorToolError.ApprovalRequired
                    val sql = call.arguments["sql"] ?: throw IllegalArgumentException("sql is required")
                    sqlSandbox.refreshWorkspaceViews(workspace)
                    val res = sqlSandbox.execute(sql)
                    ToolExecOutcome(res.output)
                }
                "run_js" -> {
                    if (mode == ChatMode.CHAT) throw DirectorToolError.ApprovalRequired
                    val code = call.arguments["code"] ?: throw IllegalArgumentException("code is required")
                    val sandbox = ScriptSandbox(workspace, allowWrites = mode == ChatMode.WORK)
                    val res = sandbox.run(code)
                    for (edit in res.proposals) {
                        proposals += edit
                        onProposal(edit)
                    }
                    ToolExecOutcome(res.output)
                }
                "read_url" -> {
                    val url = call.arguments["url"] ?: throw IllegalArgumentException("url is required")
                    if (!ReadUrl.isAllowed(url, allowedUrls)) {
                        return ToolExecOutcome(
                            "Blocked: only URLs the user shared in this chat or returned by an approved web search may be read.",
                            isError = true
                        )
                    }
                    ToolExecOutcome(host.readUrl(url))
                }
                "web_search" -> {
                    val query = call.arguments["query"]?.trim().orEmpty()
                    if (query.isEmpty()) throw IllegalArgumentException("query is required")
                    val approved = host.requestWebSearchApproval(query)
                    if (!approved) {
                        return ToolExecOutcome("User declined the web search request for \"$query\".", isError = false)
                    }
                    val items = host.searchWeb(query)
                    items.forEach { allowedUrls += it.url }
                    ToolExecOutcome(WebSearch.formatResults(query, items))
                }
                "remember" -> {
                    val fact = UserMemory.normalizeFact(call.arguments["fact"].orEmpty())
                        ?: throw IllegalArgumentException("fact is required")
                    val saved = host.requestMemoryApproval(fact)
                    ToolExecOutcome(if (saved) "Saved memory: $fact" else "User chose not to save that fact.")
                }
                "github_repos" -> {
                    val limit = call.arguments["limit"]?.toIntOrNull() ?: 20
                    ToolExecOutcome(host.listGitHubRepos(limit))
                }
                "github_open" -> {
                    val repo = call.arguments["repository"] ?: throw IllegalArgumentException("repository is required")
                    ToolExecOutcome(host.openGitHubRepo(repo))
                }
                "recall" -> {
                    val query = call.arguments["query"] ?: throw IllegalArgumentException("query is required")
                    ToolExecOutcome(host.recall(query))
                }
                "run_ci", "finish_task" -> throw DirectorToolError.ApprovalRequired
                else -> {
                    if (McpConnector.isConnectorCall(call.name)) {
                        ToolExecOutcome(host.callMcpTool(call.name, call.arguments))
                    } else {
                        throw DirectorToolError.InvalidCall("Unknown tool: ${call.name}")
                    }
                }
            }
        } catch (e: Exception) {
            ToolExecOutcome("Error: ${e.message ?: e::class.simpleName}", isError = true)
        }
    }
}
