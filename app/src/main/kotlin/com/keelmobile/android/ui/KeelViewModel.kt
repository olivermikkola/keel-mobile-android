package com.keelmobile.android.ui

import com.keelmobile.android.data.AndroidKeystoreCredentialStore
import com.keelmobile.android.data.AndroidSqliteDb
import com.keelmobile.android.data.AndroidToolHost
import com.keelmobile.android.data.CredentialStore
import com.keelmobile.android.data.LibraryItem
import com.keelmobile.android.data.ModelLibraryManager
import com.keelmobile.android.data.ModelLibraryState
import com.keelmobile.android.data.SavedMessage
import com.keelmobile.android.data.SavedTask
import com.keelmobile.android.data.TaskStore
import com.keelmobile.android.data.UserPreferences
import com.keelmobile.android.inference.AndroidLocalInference
import com.keelmobile.android.inference.LlamaNative
import com.keelmobile.harness.AgentLoop
import com.keelmobile.harness.AgentPrompt
import com.keelmobile.harness.AgentStepEvent
import com.keelmobile.harness.AppPreset
import com.keelmobile.harness.ChatMode
import com.keelmobile.harness.CustomInstructions
import com.keelmobile.harness.FileView
import com.keelmobile.harness.HarnessMessage
import com.keelmobile.harness.InferenceCancellation
import com.keelmobile.harness.InferenceTelemetry
import com.keelmobile.harness.LocalInferenceEngine
import com.keelmobile.harness.LocalModel
import com.keelmobile.harness.McpServerConfig
import com.keelmobile.harness.MemoryFact
import com.keelmobile.harness.PendingApproval
import com.keelmobile.harness.ProposedEdit
import com.keelmobile.harness.ReadUrl
import com.keelmobile.harness.SqlSandbox
import com.keelmobile.harness.TaskWorkspace
import com.keelmobile.harness.ThermalLevel
import com.keelmobile.harness.ThermalPolicy
import com.keelmobile.harness.UserMemory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.InputStream
import java.util.UUID

enum class KeelRoute {
    CHAT,
    MODELS,
    LIBRARY,
    CUSTOMIZE,
    CONNECTIONS,
    ABOUT
}

data class PreviewPayload(
    val title: String,
    val path: String,
    val html: String,
    val rawText: String
)

data class KeelUiState(
    val route: KeelRoute = KeelRoute.CHAT,
    val tasks: List<SavedTask> = emptyList(),
    val activeTaskId: String = "",
    val modelState: ModelLibraryState = ModelLibraryState(),
    val preferences: UserPreferences = UserPreferences(),
    val connectors: List<McpServerConfig> = emptyList(),
    val libraryItems: List<LibraryItem> = emptyList(),
    val isGenerating: Boolean = false,
    val streamingReply: String = "",
    val liveSteps: List<AgentStepEvent> = emptyList(),
    val thinkingEnabled: Boolean = false,
    val pendingApproval: PendingApproval? = null,
    val preview: PreviewPayload? = null,
    val bannerMessage: String? = null,
    val lastTelemetry: InferenceTelemetry? = null,
    val deviceTotalRamBytes: Long = 8L * 1024 * 1024 * 1024,
    val hasGitHubToken: Boolean = false,
    val hasBraveKey: Boolean = false,
    val systemInfo: String = ""
) {
    val activeTask: SavedTask
        get() = tasks.firstOrNull { it.id == activeTaskId } ?: SavedTask(id = activeTaskId)
}

class KeelController(
    private val taskStore: TaskStore,
    val modelLibrary: ModelLibraryManager,
    private val credentials: CredentialStore,
    private val engine: LocalInferenceEngine,
    private val totalRamBytes: Long,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
) {
    private val _state = MutableStateFlow(KeelUiState())
    val state: StateFlow<KeelUiState> = _state.asStateFlow()

    private var activeCancellation: InferenceCancellation? = null
    private var activeJob: Job? = null
    private var approvalDeferred: CompletableDeferred<Boolean>? = null
    private val activeWorkspaces = mutableMapOf<String, TaskWorkspace>()
    private val activeSqlSandboxes = mutableMapOf<String, SqlSandbox>()

    init {
        val loadedTasks = taskStore.loadTasks().ifEmpty { listOf(SavedTask()) }
        val prefs = taskStore.loadPreferences()
        _state.value = KeelUiState(
            tasks = loadedTasks,
            activeTaskId = loadedTasks.first().id,
            modelState = modelLibrary.state.value,
            preferences = prefs,
            connectors = taskStore.allConnectors(prefs),
            libraryItems = taskStore.buildLibraryIndex(loadedTasks),
            deviceTotalRamBytes = totalRamBytes,
            hasGitHubToken = credentials.has(AndroidKeystoreCredentialStore.KEY_GITHUB_TOKEN),
            hasBraveKey = credentials.has(AndroidKeystoreCredentialStore.KEY_BRAVE_SEARCH),
            systemInfo = LlamaNative.safeSystemInfo()
        )

        scope.launch {
            modelLibrary.state.collect { ms ->
                _state.update { it.copy(modelState = ms) }
            }
        }
    }

    fun navigate(route: KeelRoute) {
        _state.update { it.copy(route = route, bannerMessage = null) }
    }

    fun dismissBanner() {
        _state.update { it.copy(bannerMessage = null) }
    }

    fun toggleThinking() {
        _state.update { it.copy(thinkingEnabled = !it.thinkingEnabled) }
    }

    fun setMode(mode: ChatMode) {
        val task = _state.value.activeTask
        if (task.appPreset != null || task.repositoryName != null) return
        updateTask(task.copy(mode = mode))
    }

    fun startNewChat(preset: AppPreset? = null) {
        stopGeneration()
        val existingEmpty = _state.value.tasks.firstOrNull {
            it.messages.isEmpty() && it.appPreset == preset && it.repositoryName == null
        }
        if (existingEmpty != null) {
            _state.update { it.copy(activeTaskId = existingEmpty.id, route = KeelRoute.CHAT) }
            return
        }
        val fresh = SavedTask(
            title = preset?.title ?: "New chat",
            mode = if (preset != null) ChatMode.WORK else _state.value.activeTask.mode,
            appPreset = preset
        )
        val updated = (listOf(fresh) + _state.value.tasks).take(TaskStore.MAX_TASKS)
        persistTasks(updated, fresh.id)
        _state.update { it.copy(route = KeelRoute.CHAT) }
    }

    fun selectTask(taskId: String) {
        stopGeneration()
        if (_state.value.tasks.any { it.id == taskId }) {
            _state.update { it.copy(activeTaskId = taskId, route = KeelRoute.CHAT) }
        }
    }

    fun renameTask(taskId: String, newTitle: String) {
        val clean = newTitle.trim().take(80)
        if (clean.isEmpty()) return
        val updated = _state.value.tasks.map { if (it.id == taskId) it.copy(title = clean) else it }
        persistTasks(updated, _state.value.activeTaskId)
    }

    fun deleteTask(taskId: String) {
        if (_state.value.activeTaskId == taskId) stopGeneration()
        activeWorkspaces.remove(taskId)?.close()
        activeSqlSandboxes.remove(taskId)?.close()
        taskStore.deleteWorkspace(taskId)
        val remaining = _state.value.tasks.filterNot { it.id == taskId }.ifEmpty { listOf(SavedTask()) }
        val nextId = if (_state.value.activeTaskId == taskId) remaining.first().id else _state.value.activeTaskId
        persistTasks(remaining, nextId)
    }

    fun attachFile(name: String, content: String) {
        val task = _state.value.activeTask
        val cleanPath = TaskWorkspace.normalizePath(name) ?: "attached.txt"
        val ws = workspaceFor(task.id)
        ws.putTextFile(cleanPath, content)
        val nextApplied = (task.appliedFilePaths + cleanPath).distinct()
        val notice = SavedMessage(role = "system_notice", text = "Attached $cleanPath (${content.length} chars) to this chat's workspace.")
        updateTask(task.copy(messages = task.messages + notice, appliedFilePaths = nextApplied))
    }

    fun sendMessage(rawText: String) {
        val text = rawText.trim()
        if (text.isEmpty() || _state.value.isGenerating) return

        val modelState = _state.value.modelState
        val selected = modelState.selectedModel
        if (selected.id !in modelState.installedIds) {
            _state.update {
                it.copy(bannerMessage = "Download ${selected.name} in Models first — Keel runs strictly on-device and never falls back to a cloud model.")
            }
            return
        }

        val task = _state.value.activeTask
        val title = if (task.messages.isEmpty() && task.appPreset == null) text.take(42) else task.title
        val userMsg = SavedMessage(role = "user", text = text)
        val updatedTask = task.copy(
            title = title,
            messages = task.messages + userMsg,
            updatedAtMs = System.currentTimeMillis()
        )
        updateTask(updatedTask)

        val cancellation = InferenceCancellation()
        activeCancellation = cancellation
        _state.update {
            it.copy(
                isGenerating = true,
                streamingReply = "",
                liveSteps = emptyList(),
                bannerMessage = null
            )
        }

        activeJob = scope.launch {
            try {
                val ws = workspaceFor(updatedTask.id)
                val sql = sqlSandboxFor(updatedTask.id)
                val prefs = _state.value.preferences
                val enabledConnectors = _state.value.connectors.filter { it.enabled }

                var systemPrompt = if (updatedTask.repositoryName != null) {
                    AgentPrompt.director(
                        name = updatedTask.repositoryName,
                        branch = updatedTask.repositoryBranch ?: "main",
                        map = ws.repoMap()
                    )
                } else {
                    AgentPrompt.local(
                        app = updatedTask.appPreset,
                        mode = updatedTask.mode,
                        webSearch = _state.value.hasBraveKey,
                        connectors = enabledConnectors,
                        github = _state.value.hasGitHubToken,
                        memory = prefs.memoryEnabled,
                        recall = "search" in modelState.installedIds
                    )
                }
                systemPrompt = CustomInstructions.apply(systemPrompt, prefs.customInstructions)
                if (updatedTask.repositoryName == null && updatedTask.appPreset == null) {
                    systemPrompt = UserMemory.apply(systemPrompt, prefs.memoryFacts, prefs.memoryEnabled)
                }

                val history = updatedTask.messages.filter { it.role == "user" || it.role == "assistant" }
                    .map { m ->
                        HarnessMessage(
                            if (m.role == "user") HarnessMessage.Role.USER else HarnessMessage.Role.ASSISTANT,
                            m.text
                        )
                    }.toMutableList()

                val allowedUrls = ReadUrl.extractAllowedUrls(
                    updatedTask.messages.filter { it.role == "user" }.map { it.text }
                ).toMutableSet()

                val toolHost = AndroidToolHost(
                    credentials = credentials,
                    workspaceProvider = { ws },
                    onRepositoryOpened = { repo, branch, sha ->
                        val cur = _state.value.activeTask
                        updateTask(cur.copy(repositoryName = repo, repositoryBranch = branch, repositoryCommitSha = sha))
                    },
                    onAskWebSearchApproval = { query ->
                        askApproval(PendingApproval.WebSearchQuery(UUID.randomUUID().toString(), query))
                    },
                    onAskMemoryApproval = { fact ->
                        val ok = askApproval(PendingApproval.RememberFact(UUID.randomUUID().toString(), fact))
                        if (ok) addMemoryFact(fact)
                        ok
                    }
                )

                val loop = AgentLoop(engine, ws, sql, toolHost)
                val result = loop.runTurn(
                    model = selected,
                    modelPath = modelLibrary.modelFilePath(selected).absolutePath,
                    systemPrompt = systemPrompt,
                    history = history,
                    mode = updatedTask.mode,
                    thinking = _state.value.thinkingEnabled,
                    allowedUrls = allowedUrls,
                    cancellation = cancellation,
                    onStreamText = { partial ->
                        _state.update { it.copy(streamingReply = partial) }
                    },
                    onStep = { step ->
                        _state.update { it.copy(liveSteps = it.liveSteps + step) }
                    },
                    onProposal = { edit ->
                        val cur = _state.value.activeTask
                        updateTask(cur.copy(pendingProposals = cur.pendingProposals + edit))
                    }
                )

                val latestTask = _state.value.activeTask
                val replyText = result.finalReply.ifBlank {
                    if (result.stoppedByUser) "(Stopped)" else "(Done)"
                }
                val assistantMsg = SavedMessage(
                    role = "assistant",
                    text = replyText,
                    stepLabels = result.steps.map { it.label }
                )
                updateTask(
                    latestTask.copy(
                        messages = latestTask.messages + assistantMsg,
                        updatedAtMs = System.currentTimeMillis()
                    )
                )
                _state.update {
                    it.copy(
                        isGenerating = false,
                        streamingReply = "",
                        lastTelemetry = result.lastTelemetry
                    )
                }
            } catch (e: Exception) {
                val partial = _state.value.streamingReply.trim()
                val latestTask = _state.value.activeTask
                if (partial.isNotEmpty()) {
                    updateTask(latestTask.copy(messages = latestTask.messages + SavedMessage(role = "assistant", text = partial)))
                }
                _state.update {
                    it.copy(
                        isGenerating = false,
                        streamingReply = "",
                        bannerMessage = e.message ?: "Local inference stopped unexpectedly."
                    )
                }
            } finally {
                activeCancellation = null
                activeJob = null
            }
        }
    }

    fun stopGeneration() {
        activeCancellation?.cancel()
        if (engine is AndroidLocalInference) engine.cancelActive()
        approvalDeferred?.complete(false)
        approvalDeferred = null
        _state.update { it.copy(pendingApproval = null) }
    }

    fun resolvePendingApproval(approved: Boolean) {
        approvalDeferred?.complete(approved)
        approvalDeferred = null
        _state.update { it.copy(pendingApproval = null) }
    }

    private suspend fun askApproval(request: PendingApproval): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        approvalDeferred = deferred
        _state.update { it.copy(pendingApproval = request) }
        return try {
            deferred.await()
        } finally {
            if (approvalDeferred === deferred) approvalDeferred = null
            _state.update { it.copy(pendingApproval = null) }
        }
    }

    fun applyProposal(editId: String) {
        val task = _state.value.activeTask
        val proposal = task.pendingProposals.firstOrNull { it.id == editId } ?: return
        val ws = workspaceFor(task.id)
        try {
            ws.applyProposal(proposal)
            val remaining = task.pendingProposals.filterNot { it.id == editId }
            val nextApplied = when (proposal.operation) {
                com.keelmobile.harness.EditOperation.DELETE -> task.appliedFilePaths.filterNot { it == proposal.path }
                com.keelmobile.harness.EditOperation.RENAME ->
                    (task.appliedFilePaths.filterNot { it == proposal.path } + listOfNotNull(proposal.destinationPath)).distinct()
                else -> (task.appliedFilePaths + proposal.path).distinct()
            }
            updateTask(task.copy(pendingProposals = remaining, appliedFilePaths = nextApplied))
            _state.update { it.copy(bannerMessage = "Applied ${proposal.path}") }
        } catch (e: Exception) {
            _state.update { it.copy(bannerMessage = e.message ?: "Could not apply proposal.") }
        }
    }

    fun discardProposal(editId: String) {
        val task = _state.value.activeTask
        updateTask(task.copy(pendingProposals = task.pendingProposals.filterNot { it.id == editId }))
    }

    fun previewProposal(edit: ProposedEdit) {
        val path = edit.destinationPath ?: edit.path
        val text = edit.newText ?: workspaceFor(_state.value.activeTaskId).readText(edit.path).orEmpty()
        openPreview(path, text, _state.value.activeTaskId)
    }

    fun previewLibraryFile(item: LibraryItem) {
        val ws = workspaceFor(item.taskId)
        val text = ws.readText(item.path) ?: return
        openPreview(item.path, text, item.taskId)
    }

    private fun openPreview(path: String, text: String, taskId: String) {
        val ext = path.substringAfterLast('.', "").lowercase()
        val html = when (ext) {
            "html", "htm" -> {
                val ws = workspaceFor(taskId)
                if (ws.readText(path) == text) FileView.renderHtmlPreview(path, ws)
                else """<meta http-equiv="Content-Security-Policy" content="${FileView.OFFLINE_CSP}">\n$text"""
            }
            "csv" -> FileView.renderCsv(path, text)
            "md" -> FileView.renderMarkdown(path, text)
            else -> FileView.renderMarkdown(path, "```\n$text\n```")
        }
        _state.update { it.copy(preview = PreviewPayload(path.substringAfterLast('/'), path, html, text)) }
    }

    fun closePreview() {
        _state.update { it.copy(preview = null) }
    }

    fun saveCustomInstructions(text: String) {
        val next = _state.value.preferences.copy(customInstructions = CustomInstructions.normalize(text))
        savePreferences(next)
    }

    fun setMemoryEnabled(enabled: Boolean) {
        savePreferences(_state.value.preferences.copy(memoryEnabled = enabled))
    }

    fun addMemoryFact(factText: String) {
        val clean = UserMemory.normalizeFact(factText) ?: return
        val fact = MemoryFact(UUID.randomUUID().toString(), clean, System.currentTimeMillis())
        val next = _state.value.preferences.copy(memoryFacts = listOf(fact) + _state.value.preferences.memoryFacts)
        savePreferences(next)
    }

    fun forgetMemoryFact(id: String) {
        val next = _state.value.preferences.copy(
            memoryFacts = _state.value.preferences.memoryFacts.filterNot { it.id == id }
        )
        savePreferences(next)
    }

    fun clearAllMemory() {
        savePreferences(_state.value.preferences.copy(memoryFacts = emptyList()))
    }

    fun toggleConnector(serverId: String) {
        val cur = _state.value.preferences.enabledConnectorIds
        val nextSet = if (serverId in cur) cur - serverId else cur + serverId
        savePreferences(_state.value.preferences.copy(enabledConnectorIds = nextSet))
    }

    fun saveGitHubToken(token: String) {
        credentials.put(AndroidKeystoreCredentialStore.KEY_GITHUB_TOKEN, token)
        _state.update {
            it.copy(
                hasGitHubToken = credentials.has(AndroidKeystoreCredentialStore.KEY_GITHUB_TOKEN),
                bannerMessage = if (token.isBlank()) "Disconnected GitHub." else "GitHub token saved in Android Keystore."
            )
        }
    }

    fun saveBraveKey(apiKey: String) {
        credentials.put(AndroidKeystoreCredentialStore.KEY_BRAVE_SEARCH, apiKey)
        _state.update {
            it.copy(
                hasBraveKey = credentials.has(AndroidKeystoreCredentialStore.KEY_BRAVE_SEARCH),
                bannerMessage = if (apiKey.isBlank()) "Removed Brave Search key." else "Brave Search key saved in Android Keystore."
            )
        }
    }

    fun importLocalModel(displayName: String, inputProvider: () -> InputStream) {
        scope.launch {
            try {
                val model = modelLibrary.importLocalStream(displayName, inputProvider)
                modelLibrary.selectModel(model.id)
                _state.update { it.copy(bannerMessage = "Imported ${model.name} and selected it.") }
            } catch (e: Exception) {
                _state.update { it.copy(bannerMessage = e.message ?: "Could not import GGUF file.") }
            }
        }
    }

    fun importHuggingFaceLink(url: String) {
        try {
            val model = modelLibrary.importFromHuggingFaceLink(url)
            _state.update { it.copy(bannerMessage = "Started downloading ${model.name}.") }
        } catch (e: Exception) {
            _state.update { it.copy(bannerMessage = e.message ?: "Invalid Hugging Face link.") }
        }
    }

    fun onMemoryPressure() {
        stopGeneration()
        scope.launch {
            if (engine is AndroidLocalInference) engine.unload()
        }
        _state.update {
            it.copy(bannerMessage = "Memory is tight. Generation stopped and the model was unloaded cleanly.")
        }
    }

    fun onThermalStatusChanged(level: ThermalLevel) {
        if (!ThermalPolicy.shouldStop(level)) return
        stopGeneration()
        scope.launch {
            if (engine is AndroidLocalInference) engine.unload()
        }
        _state.update { it.copy(bannerMessage = ThermalPolicy.userMessage(level)) }
    }

    private fun workspaceFor(taskId: String): TaskWorkspace =
        activeWorkspaces.getOrPut(taskId) { taskStore.openWorkspace(taskId) }

    private fun sqlSandboxFor(taskId: String): SqlSandbox =
        activeSqlSandboxes.getOrPut(taskId) { SqlSandbox(AndroidSqliteDb.openInMemory()) }

    private fun updateTask(updated: SavedTask) {
        val list = _state.value.tasks.map { if (it.id == updated.id) updated else it }
        persistTasks(list, _state.value.activeTaskId)
    }

    private fun persistTasks(list: List<SavedTask>, activeId: String) {
        taskStore.saveTasks(list)
        _state.update {
            it.copy(
                tasks = list,
                activeTaskId = activeId,
                libraryItems = taskStore.buildLibraryIndex(list)
            )
        }
    }

    private fun savePreferences(prefs: UserPreferences) {
        taskStore.savePreferences(prefs)
        _state.update {
            it.copy(
                preferences = prefs,
                connectors = taskStore.allConnectors(prefs)
            )
        }
    }
}
