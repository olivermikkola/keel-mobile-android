package com.keelmobile.android.ui

import android.net.Uri
import com.keelmobile.android.data.AndroidKeystoreCredentialStore
import com.keelmobile.android.data.AndroidSqliteDb
import com.keelmobile.android.data.AndroidToolHost
import com.keelmobile.android.data.CredentialStore
import com.keelmobile.android.data.GitHubPublisher
import com.keelmobile.android.data.LibraryItem
import com.keelmobile.android.data.LocalFolderBridge
import com.keelmobile.android.data.ModelLibraryManager
import com.keelmobile.android.data.ModelLibraryState
import com.keelmobile.android.data.PhotoAttachmentHelper
import com.keelmobile.android.data.RecallStore
import com.keelmobile.android.data.SavedMessage
import com.keelmobile.android.data.SavedTask
import com.keelmobile.android.data.ScheduledTaskStore
import com.keelmobile.android.data.TaskStore
import com.keelmobile.android.data.UserPreferences
import com.keelmobile.android.inference.AndroidLocalInference
import com.keelmobile.android.inference.BenchmarkSummary
import com.keelmobile.android.inference.LlamaNative
import com.keelmobile.harness.AgentLoop
import com.keelmobile.harness.AgentPrompt
import com.keelmobile.harness.AgentStepEvent
import com.keelmobile.harness.AppPreset
import com.keelmobile.harness.ChatMode
import com.keelmobile.harness.CustomInstructions
import com.keelmobile.harness.FileView
import com.keelmobile.harness.GitHubPublishPlanner
import com.keelmobile.harness.HarnessMessage
import com.keelmobile.harness.InferenceCancellation
import com.keelmobile.harness.InferenceTelemetry
import com.keelmobile.harness.LocalInferenceEngine
import com.keelmobile.harness.McpServerConfig
import com.keelmobile.harness.MemoryFact
import com.keelmobile.harness.PendingApproval
import com.keelmobile.harness.ProposedEdit
import com.keelmobile.harness.ReadUrl
import com.keelmobile.harness.ScheduleInterval
import com.keelmobile.harness.ScheduledTask
import com.keelmobile.harness.SqlSandbox
import com.keelmobile.harness.TaskWorkspace
import com.keelmobile.harness.ThermalLevel
import com.keelmobile.harness.ThermalPolicy
import com.keelmobile.harness.UserMemory
import com.keelmobile.harness.VisionImage
import com.keelmobile.harness.WorkspaceChange
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
    SCHEDULED,
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

data class PublishSheetState(
    val repository: String,
    val baseBranch: String,
    val baseCommitSha: String,
    val changes: List<WorkspaceChange>,
    val isPublishing: Boolean = false,
    val publishedUrl: String? = null,
    val error: String? = null
)

data class KeelUiState(
    val route: KeelRoute = KeelRoute.CHAT,
    val tasks: List<SavedTask> = emptyList(),
    val activeTaskId: String = "",
    val modelState: ModelLibraryState = ModelLibraryState(),
    val preferences: UserPreferences = UserPreferences(),
    val connectors: List<McpServerConfig> = emptyList(),
    val libraryItems: List<LibraryItem> = emptyList(),
    val scheduledTasks: List<ScheduledTask> = emptyList(),
    val isGenerating: Boolean = false,
    val streamingReply: String = "",
    val liveSteps: List<AgentStepEvent> = emptyList(),
    val thinkingEnabled: Boolean = false,
    val pendingPhotos: List<VisionImage> = emptyList(),
    val pendingApproval: PendingApproval? = null,
    val preview: PreviewPayload? = null,
    val publishSheet: PublishSheetState? = null,
    val bannerMessage: String? = null,
    val lastTelemetry: InferenceTelemetry? = null,
    val isBenchmarking: Boolean = false,
    val lastBenchmark: BenchmarkSummary? = null,
    val deviceTotalRamBytes: Long = 8L * 1024 * 1024 * 1024,
    val deviceAvailableRamBytes: Long = 4L * 1024 * 1024 * 1024,
    val freeStorageBytes: Long = 32L * 1024 * 1024 * 1024,
    val vulkanGpuAvailable: Boolean = false,
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
    private val availableRamBytes: Long = totalRamBytes / 2,
    private val freeStorageBytes: Long = 32L * 1024 * 1024 * 1024,
    private val scheduledStore: ScheduledTaskStore? = null,
    private val onSyncSchedule: ((ScheduledTask) -> Unit)? = null,
    private val onCancelSchedule: ((String) -> Unit)? = null,
    private val localFolderBridge: LocalFolderBridge? = null,
    private val recallStore: RecallStore? = null,
    private val gitHubPublisher: GitHubPublisher = GitHubPublisher(credentials),
    private val onInferenceForegroundStatus: ((modelName: String, status: String, running: Boolean) -> Unit)? = null,
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
        if (engine is AndroidLocalInference) {
            engine.preferVulkanGpu = prefs.useVulkanGpu
        }
        _state.value = KeelUiState(
            tasks = loadedTasks,
            activeTaskId = loadedTasks.first().id,
            modelState = modelLibrary.state.value,
            preferences = prefs,
            connectors = taskStore.allConnectors(prefs),
            libraryItems = taskStore.buildLibraryIndex(loadedTasks),
            scheduledTasks = scheduledStore?.load().orEmpty(),
            deviceTotalRamBytes = totalRamBytes,
            deviceAvailableRamBytes = availableRamBytes,
            freeStorageBytes = freeStorageBytes,
            vulkanGpuAvailable = LlamaNative.safeHasGpuBackend(),
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
        if (route == KeelRoute.SCHEDULED && scheduledStore != null) {
            _state.update { it.copy(scheduledTasks = scheduledStore.load()) }
        }
        _state.update { it.copy(route = route, bannerMessage = null) }
    }

    fun dismissBanner() {
        _state.update { it.copy(bannerMessage = null) }
    }

    fun toggleThinking() {
        _state.update { it.copy(thinkingEnabled = !it.thinkingEnabled) }
    }

    fun setUseVulkanGpu(enabled: Boolean) {
        if (engine is AndroidLocalInference) {
            engine.preferVulkanGpu = enabled
        }
        savePreferences(_state.value.preferences.copy(useVulkanGpu = enabled))
    }

    fun setMode(mode: ChatMode) {
        val task = _state.value.activeTask
        if (task.appPreset != null || task.repositoryName != null || task.localFolderUri != null) return
        updateTask(task.copy(mode = mode))
    }

    fun startNewChat(preset: AppPreset? = null) {
        stopGeneration()
        val existingEmpty = _state.value.tasks.firstOrNull {
            it.messages.isEmpty() && it.appPreset == preset && it.repositoryName == null && it.localFolderUri == null
        }
        if (existingEmpty != null) {
            _state.update { it.copy(activeTaskId = existingEmpty.id, route = KeelRoute.CHAT, pendingPhotos = emptyList()) }
            return
        }
        val fresh = SavedTask(
            title = preset?.title ?: "New chat",
            mode = if (preset != null) ChatMode.WORK else _state.value.activeTask.mode,
            appPreset = preset
        )
        val updated = (listOf(fresh) + _state.value.tasks).take(TaskStore.MAX_TASKS)
        persistTasks(updated, fresh.id)
        _state.update { it.copy(route = KeelRoute.CHAT, pendingPhotos = emptyList()) }
    }

    fun selectTask(taskId: String) {
        stopGeneration()
        val latest = taskStore.loadTasks().ifEmpty { _state.value.tasks }
        if (latest.any { it.id == taskId }) {
            _state.update {
                it.copy(
                    tasks = latest,
                    activeTaskId = taskId,
                    route = KeelRoute.CHAT,
                    pendingPhotos = emptyList()
                )
            }
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

    fun attachPhoto(photo: VisionImage) {
        val cur = _state.value.pendingPhotos
        if (cur.size >= PhotoAttachmentHelper.MAX_PHOTOS_PER_TURN) {
            _state.update { it.copy(bannerMessage = "Up to ${PhotoAttachmentHelper.MAX_PHOTOS_PER_TURN} photos per message.") }
            return
        }
        val selected = _state.value.modelState.selectedModel
        val hint = if (selected.visionFile == null) {
            "Photo attached. Switch to LFM3 VL · 2B in Models to send photos to the local vision model."
        } else null
        _state.update { it.copy(pendingPhotos = cur + photo, bannerMessage = hint) }
    }

    fun removePendingPhoto(index: Int) {
        _state.update { st ->
            st.copy(pendingPhotos = st.pendingPhotos.filterIndexed { i, _ -> i != index })
        }
    }

    /**
     * Opens a real local Android folder (Storage Access Framework tree URI) into
     * a Work chat. Approved edits write straight back to the real files on storage!
     */
    fun openLocalFolder(treeUri: Uri) {
        val bridge = localFolderBridge ?: return
        scope.launch {
            try {
                val fresh = SavedTask(
                    title = "Folder workspace",
                    mode = ChatMode.WORK,
                    localFolderUri = treeUri.toString()
                )
                val ws = workspaceFor(fresh.id)
                val summary = bridge.importFolderIntoWorkspace(treeUri, ws)
                val notice = SavedMessage(
                    role = "system_notice",
                    text = "Opened local folder '${summary.folderName}' (${summary.importedFiles} text files loaded, ${summary.skippedFiles} skipped). Approved edits sync directly back to this folder."
                )
                val ready = fresh.copy(
                    title = summary.folderName,
                    localFolderName = summary.folderName,
                    messages = listOf(notice)
                )
                val updated = (listOf(ready) + _state.value.tasks).take(TaskStore.MAX_TASKS)
                persistTasks(updated, ready.id)
                _state.update { it.copy(route = KeelRoute.CHAT, bannerMessage = "Opened folder '${summary.folderName}' (${summary.importedFiles} files).") }
            } catch (e: Exception) {
                _state.update { it.copy(bannerMessage = e.message ?: "Could not open folder.") }
            }
        }
    }

    fun receiveSharedText(sharedText: String) {
        val clean = sharedText.trim()
        if (clean.isEmpty()) return
        val task = _state.value.activeTask
        val notice = SavedMessage(role = "system_notice", text = "Shared into Keel:\n$clean")
        updateTask(task.copy(messages = task.messages + notice))
        _state.update { it.copy(route = KeelRoute.CHAT, bannerMessage = "Received shared content. Ask Keel what to do with it.") }
    }

    fun exportActiveChatMarkdown(): PreviewPayload {
        val task = _state.value.activeTask
        val md = buildString {
            append("# ${task.title}\n\n")
            for (m in task.messages) {
                when (m.role) {
                    "user" -> append("## You\n\n${m.text}\n\n")
                    "assistant" -> append("## Keel\n\n${m.text}\n\n")
                    else -> append("> ${m.text}\n\n")
                }
            }
        }
        val safeName = task.title.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "chat" } + ".md"
        return PreviewPayload(safeName, safeName, FileView.renderMarkdown(task.title, md), md)
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

        val photosToSend = _state.value.pendingPhotos
        if (photosToSend.isNotEmpty() && selected.visionFile == null) {
            _state.update {
                it.copy(bannerMessage = "${selected.name} is text-only. Switch to LFM3 VL · 2B in Models or remove the attached photo.")
            }
            return
        }

        val task = _state.value.activeTask
        val title = if (task.messages.isEmpty() && task.appPreset == null && task.localFolderName == null) text.take(42) else task.title
        val displayMsg = if (photosToSend.isNotEmpty()) "$text\n[${photosToSend.size} photo(s) attached]" else text
        val userMsg = SavedMessage(role = "user", text = displayMsg)
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
                pendingPhotos = emptyList(),
                bannerMessage = null
            )
        }
        onInferenceForegroundStatus?.invoke(selected.name, "Working locally…", true)

        activeJob = scope.launch {
            try {
                val ws = workspaceFor(updatedTask.id)
                val sql = sqlSandboxFor(updatedTask.id)
                val prefs = _state.value.preferences
                val enabledConnectors = _state.value.connectors.filter { it.enabled }

                var systemPrompt = when {
                    updatedTask.repositoryName != null -> AgentPrompt.director(
                        name = updatedTask.repositoryName,
                        branch = updatedTask.repositoryBranch ?: "main",
                        map = ws.repoMap()
                    )
                    updatedTask.localFolderName != null -> AgentPrompt.director(
                        name = "local folder '${updatedTask.localFolderName}'",
                        branch = "local",
                        map = ws.repoMap()
                    )
                    else -> AgentPrompt.local(
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
                    updatedTask.messages.filter { it.role == "user" || it.role == "system_notice" }.map { it.text }
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
                    },
                    onRecall = { query ->
                        recallStore?.searchByMeaning(query, updatedTask.id)
                            ?: "Search by meaning is not installed."
                    }
                )

                val projectorPath = selected.visionFile?.let { modelLibrary.assetFile(it).absolutePath }
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
                    projectorPath = projectorPath,
                    pendingImages = photosToSend,
                    onStreamText = { partial ->
                        _state.update { it.copy(streamingReply = partial) }
                    },
                    onStep = { step ->
                        onInferenceForegroundStatus?.invoke(selected.name, step.label, true)
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
                onInferenceForegroundStatus?.invoke(selected.name, "Done", false)
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
        onInferenceForegroundStatus?.invoke("", "", false)
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

            // If this chat is backed by a real Android folder, write the approved edit straight back to storage!
            val folderUriStr = task.localFolderUri
            val bridge = localFolderBridge
            if (folderUriStr != null && bridge != null) {
                val finalContent = ws.readText(proposal.destinationPath ?: proposal.path)
                scope.launch {
                    val synced = bridge.writeApprovedChangeToFolder(Uri.parse(folderUriStr), proposal, finalContent)
                    _state.update {
                        it.copy(
                            bannerMessage = if (synced) "Applied ${proposal.path} and saved to '${task.localFolderName}'."
                            else "Applied ${proposal.path} in chat (could not write back to local folder)."
                        )
                    }
                }
            } else {
                _state.update { it.copy(bannerMessage = "Applied ${proposal.path}") }
            }
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

    // --- GitHub Publish Sheet ---

    fun openPublishSheet() {
        val task = _state.value.activeTask
        val repo = task.repositoryName ?: return
        val branch = task.repositoryBranch ?: "main"
        val sha = task.repositoryCommitSha ?: return
        val changes = workspaceFor(task.id).diffAgainstBase()
        if (changes.isEmpty()) {
            _state.update { it.copy(bannerMessage = "Apply at least one proposed change before publishing to GitHub.") }
            return
        }
        _state.update {
            it.copy(publishSheet = PublishSheetState(repo, branch, sha, changes))
        }
    }

    fun closePublishSheet() {
        _state.update { it.copy(publishSheet = null) }
    }

    fun confirmPublishToGitHub(headBranch: String, commitMessage: String, prTitle: String) {
        val sheet = _state.value.publishSheet ?: return
        val plan = try {
            GitHubPublishPlanner.validate(
                repository = sheet.repository,
                baseBranch = sheet.baseBranch,
                baseCommitSha = sheet.baseCommitSha,
                headBranch = headBranch,
                commitMessage = commitMessage,
                prTitle = prTitle,
                changes = sheet.changes
            )
        } catch (e: Exception) {
            _state.update { it.copy(publishSheet = sheet.copy(error = e.message)) }
            return
        }

        _state.update { it.copy(publishSheet = sheet.copy(isPublishing = true, error = null)) }
        scope.launch {
            try {
                val res = gitHubPublisher.publishAndOpenPr(plan)
                _state.update {
                    it.copy(
                        publishSheet = sheet.copy(isPublishing = false, publishedUrl = res.pullRequestUrl),
                        bannerMessage = "Published branch ${res.branch} and opened Pull Request."
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(publishSheet = sheet.copy(isPublishing = false, error = e.message ?: "Publish failed."))
                }
            }
        }
    }

    // --- Unattended Scheduled Tasks (Android WorkManager) ---

    fun addScheduledTask(title: String, prompt: String, interval: ScheduleInterval) {
        val store = scheduledStore ?: return
        try {
            val (cleanTitle, cleanPrompt) = ScheduledTask.validate(title, prompt)
            val created = ScheduledTask(title = cleanTitle, prompt = cleanPrompt, interval = interval)
            val next = (listOf(created) + _state.value.scheduledTasks).take(ScheduledTask.MAX_SCHEDULED)
            store.save(next)
            onSyncSchedule?.invoke(created)
            _state.update { it.copy(scheduledTasks = next, bannerMessage = "Scheduled '${created.title}' (${interval.label}).") }
        } catch (e: Exception) {
            _state.update { it.copy(bannerMessage = e.message ?: "Could not add scheduled task.") }
        }
    }

    fun toggleScheduledTask(id: String, enabled: Boolean) {
        val store = scheduledStore ?: return
        val next = _state.value.scheduledTasks.map { if (it.id == id) it.copy(enabled = enabled) else it }
        store.save(next)
        next.firstOrNull { it.id == id }?.let { onSyncSchedule?.invoke(it) }
        _state.update { it.copy(scheduledTasks = next) }
    }

    fun deleteScheduledTask(id: String) {
        val store = scheduledStore ?: return
        onCancelSchedule?.invoke(id)
        val next = _state.value.scheduledTasks.filterNot { it.id == id }
        store.save(next)
        _state.update { it.copy(scheduledTasks = next) }
    }

    fun runScheduledTaskNow(id: String) {
        val sched = _state.value.scheduledTasks.firstOrNull { it.id == id } ?: return
        startNewChat(null)
        sendMessage(sched.prompt)
    }

    // --- On-Device Benchmark ---

    fun runSelectedModelBenchmark() {
        if (_state.value.isBenchmarking || _state.value.isGenerating) return
        val selected = _state.value.modelState.selectedModel
        if (selected.id !in _state.value.modelState.installedIds) {
            _state.update { it.copy(bannerMessage = "Download ${selected.name} first to benchmark it.") }
            return
        }
        val localInf = engine as? AndroidLocalInference ?: return
        _state.update { it.copy(isBenchmarking = true, lastBenchmark = null) }
        scope.launch {
            try {
                val res = localInf.benchmarkModel(selected, modelLibrary.modelFilePath(selected).absolutePath)
                _state.update { it.copy(isBenchmarking = false, lastBenchmark = res) }
            } catch (e: Exception) {
                _state.update { it.copy(isBenchmarking = false, bannerMessage = e.message ?: "Benchmark failed.") }
            }
        }
    }

    // --- Preferences & Connections ---

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
