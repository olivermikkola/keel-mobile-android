package com.keelmobile.android.ui

import android.annotation.SuppressLint
import android.content.Context
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.keelmobile.android.data.LibraryItem
import com.keelmobile.android.ui.theme.KeelMark
import com.keelmobile.android.ui.theme.KeelPalette
import com.keelmobile.android.ui.theme.LocalKeelColors
import com.keelmobile.harness.CustomInstructions
import com.keelmobile.harness.ScheduleInterval
import com.keelmobile.harness.ScheduledTask
import java.io.ByteArrayInputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    items: List<LibraryItem>,
    onBack: () -> Unit,
    onOpenItem: (LibraryItem) -> Unit
) {
    val colors = LocalKeelColors.current
    var filter by remember { mutableStateOf("All") }
    val filters = listOf("All", "Pages", "Docs", "Tables", "Files")
    val shown = remember(items, filter) {
        if (filter == "All") items else items.filter { it.kind == filter }
    }

    Column(modifier = Modifier.fillMaxSize().background(colors.background)) {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background, titleContentColor = colors.textPrimary),
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.textPrimary) } },
            title = { Text("Library") }
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            filters.forEach { f ->
                Surface(
                    onClick = { filter = f },
                    shape = RoundedCornerShape(16.dp),
                    color = if (filter == f) colors.accent else colors.surface,
                    border = BorderStroke(1.dp, colors.hairline)
                ) {
                    Text(
                        text = f,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (filter == f) colors.background else colors.textPrimary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }

        if (shown.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("No files yet", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Files you apply from websites, sheets, docs, study decks, or plain chats appear here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(shown, key = { "${it.taskId}:${it.path}" }) { item ->
                    Surface(
                        onClick = { onOpenItem(item) },
                        shape = RoundedCornerShape(14.dp),
                        color = colors.surface,
                        border = BorderStroke(1.dp, colors.hairline),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(item.path, style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
                            Text("${item.kind} · from ${item.taskTitle}", style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomizeScreen(
    state: KeelUiState,
    onBack: () -> Unit,
    onSaveInstructions: (String) -> Unit,
    onSetMemoryEnabled: (Boolean) -> Unit,
    onForgetFact: (String) -> Unit,
    onClearAllMemory: () -> Unit
) {
    val colors = LocalKeelColors.current
    var instructions by remember(state.preferences.customInstructions) {
        mutableStateOf(state.preferences.customInstructions)
    }

    Column(modifier = Modifier.fillMaxSize().background(colors.background)) {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background, titleContentColor = colors.textPrimary),
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.textPrimary) } },
            title = { Text("Customize") }
        )
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text("Standing instructions", style = MaterialTheme.typography.titleSmall, color = colors.accent)
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = instructions,
                    onValueChange = { instructions = it.take(CustomInstructions.MAX_CHARS) },
                    placeholder = { Text("e.g. Prefer metric units and concise plain-language explanations.") },
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { onSaveInstructions(instructions) },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
                ) {
                    Text("Save instructions", color = colors.background)
                }
            }

            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Memory in plain chats", style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
                        Text("Keel only saves facts when you tap Remember.", style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
                    }
                    Switch(
                        checked = state.preferences.memoryEnabled,
                        onCheckedChange = onSetMemoryEnabled
                    )
                }
            }

            if (state.preferences.memoryFacts.isNotEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("Saved facts (${state.preferences.memoryFacts.size})", style = MaterialTheme.typography.titleSmall, color = colors.textMuted, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = onClearAllMemory) { Text("Clear all") }
                    }
                }
                items(state.preferences.memoryFacts, key = { it.id }) { fact ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = colors.surface,
                        border = BorderStroke(1.dp, colors.hairline),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(fact.text, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary, modifier = Modifier.weight(1f))
                            IconButton(onClick = { onForgetFact(fact.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Forget", tint = colors.textMuted)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsScreen(
    state: KeelUiState,
    onBack: () -> Unit,
    onSaveGitHubToken: (String) -> Unit,
    onSaveBraveKey: (String) -> Unit,
    onToggleConnector: (String) -> Unit
) {
    val colors = LocalKeelColors.current
    var ghToken by remember { mutableStateOf("") }
    var braveKey by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().background(colors.background)) {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background, titleContentColor = colors.textPrimary),
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.textPrimary) } },
            title = { Text("Connections") }
        )

        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = colors.surface,
                    border = BorderStroke(1.dp, colors.hairline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("GitHub", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (state.hasGitHubToken) "Connected (token stored in Android Keystore)." else "Connect a personal access token to open repositories at a pinned commit.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textMuted
                        )
                        OutlinedTextField(
                            value = ghToken,
                            onValueChange = { ghToken = it },
                            label = { Text("Personal Access Token") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { onSaveGitHubToken(ghToken); ghToken = "" },
                                enabled = ghToken.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
                            ) { Text("Save", color = colors.background) }
                            if (state.hasGitHubToken) {
                                OutlinedButton(onClick = { onSaveGitHubToken("") }) { Text("Disconnect") }
                            }
                        }
                    }
                }
            }

            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = colors.surface,
                    border = BorderStroke(1.dp, colors.hairline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Web search (Brave Search)", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (state.hasBraveKey) "Configured in Android Keystore. Every search query still asks for your approval."
                            else "Optional. Add your own Brave Search API key to enable approval-gated web_search.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textMuted
                        )
                        OutlinedTextField(
                            value = braveKey,
                            onValueChange = { braveKey = it },
                            label = { Text("Brave Search API Key") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { onSaveBraveKey(braveKey); braveKey = "" },
                                enabled = braveKey.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
                            ) { Text("Save", color = colors.background) }
                            if (state.hasBraveKey) {
                                OutlinedButton(onClick = { onSaveBraveKey("") }) { Text("Remove") }
                            }
                        }
                    }
                }
            }

            item {
                Text("MCP Connectors", style = MaterialTheme.typography.titleSmall, color = colors.textMuted)
            }

            items(state.connectors, key = { it.id }) { srv ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = colors.surface,
                    border = BorderStroke(1.dp, colors.hairline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(srv.name, style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
                            Text(
                                if (srv.requiresAuth) "Account required · ${srv.endpointUrl}" else "No account required · ${srv.endpointUrl}",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textMuted
                            )
                        }
                        Switch(checked = srv.enabled, onCheckedChange = { onToggleConnector(srv.id) })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    systemInfo: String,
    onBack: () -> Unit
) {
    val colors = LocalKeelColors.current
    Column(modifier = Modifier.fillMaxSize().background(colors.background)) {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background, titleContentColor = colors.textPrimary),
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.textPrimary) } },
            title = { Text("About & Privacy") }
        )
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                KeelMark(modifier = Modifier.size(44.dp))
                Column {
                    Text("Keel Mobile for Android", style = MaterialTheme.typography.titleLarge, color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
                    Text("Standalone on-device AI agent", style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
                }
            }
            Text(
                "• Local means local: inference runs directly on your Android device via llama.cpp. Keel never falls back to a cloud model.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary
            )
            Text(
                "• You approve, the model asks: file changes, web searches, saved memories, and publishing each require your explicit tap.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary
            )
            Text(
                "• Secrets stay in Android Keystore, excluded from backups and never placed in prompts or logs.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary
            )
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = colors.surface,
                border = BorderStroke(1.dp, colors.hairline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Native Runtime", style = MaterialTheme.typography.labelMedium, color = colors.accent)
                    Spacer(Modifier.height(4.dp))
                    Text(systemInfo, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
                }
            }
        }
    }
}

/**
 * Locked-down offline preview WebView (zero network access, zero file:// access)
 * with Export PDF via Android PrintManager and Share.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflinePreviewDialog(
    payload: PreviewPayload,
    onDismiss: () -> Unit,
    onShare: (PreviewPayload) -> Unit
) {
    val colors = LocalKeelColors.current
    val context = LocalContext.current
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(modifier = Modifier.fillMaxSize().background(colors.background)) {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background, titleContentColor = colors.textPrimary),
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close preview", tint = colors.textPrimary)
                    }
                },
                title = { Text(payload.title) },
                actions = {
                    IconButton(onClick = {
                        webViewRef?.let { wv ->
                            val pm = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
                            val adapter = wv.createPrintDocumentAdapter(payload.title)
                            pm?.print("Keel - ${payload.title}", adapter, PrintAttributes.Builder().build())
                        }
                    }) {
                        Icon(Icons.Default.Print, contentDescription = "Export PDF", tint = colors.accent)
                    }
                    IconButton(onClick = { onShare(payload) }) {
                        Icon(Icons.Default.Share, contentDescription = "Share file", tint = colors.accent)
                    }
                }
            )
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.blockNetworkLoads = true
                        settings.blockNetworkImage = true
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse {
                                // Hard offline fence: deny every external subresource request
                                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                            }
                        }
                        loadDataWithBaseURL("https://keel.local/", payload.html, "text/html", "UTF-8", null)
                        webViewRef = this
                    }
                }
            )
        }
    }
}

/**
 * Unattended scheduled tasks backed by Android WorkManager — runs locally even when
 * the app is closed, respecting battery and thermal limits.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledTasksScreen(
    tasks: List<ScheduledTask>,
    onBack: () -> Unit,
    onAddTask: (title: String, prompt: String, interval: ScheduleInterval) -> Unit,
    onToggleTask: (String, Boolean) -> Unit,
    onRunNow: (String) -> Unit,
    onDeleteTask: (String) -> Unit
) {
    val colors = LocalKeelColors.current
    var title by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var selectedInterval by remember { mutableStateOf(ScheduleInterval.DAILY) }

    Column(modifier = Modifier.fillMaxSize().background(colors.background)) {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background, titleContentColor = colors.textPrimary),
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.textPrimary) } },
            title = { Text("Scheduled Tasks") }
        )
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = colors.surface,
                    border = BorderStroke(1.dp, colors.hairline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("New background task", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Runs unattended on-device via Android WorkManager (skipped if battery is low or the phone is hot).",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textMuted
                        )
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            label = { Text("Title (optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = prompt,
                            onValueChange = { prompt = it },
                            label = { Text("Prompt to run") },
                            minLines = 2,
                            maxLines = 5,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ScheduleInterval.entries.forEach { interval ->
                                Surface(
                                    onClick = { selectedInterval = interval },
                                    shape = RoundedCornerShape(14.dp),
                                    color = if (selectedInterval == interval) colors.accent else colors.surfaceRaised
                                ) {
                                    Text(
                                        text = interval.label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (selectedInterval == interval) colors.background else colors.textPrimary,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                        Button(
                            onClick = {
                                onAddTask(title, prompt, selectedInterval)
                                title = ""
                                prompt = ""
                            },
                            enabled = prompt.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
                        ) {
                            Text("Schedule locally", color = colors.background)
                        }
                    }
                }
            }

            if (tasks.isEmpty()) {
                item {
                    Text(
                        "No scheduled tasks yet. Create one above to run prompts automatically on-device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMuted
                    )
                }
            } else {
                items(tasks, key = { it.id }) { item ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = colors.surface,
                        border = BorderStroke(1.dp, colors.hairline),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(item.title, style = MaterialTheme.typography.titleSmall, color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
                                    Text("${item.interval.label} · ${item.lastStatus}", style = MaterialTheme.typography.labelSmall, color = colors.accent)
                                }
                                Switch(checked = item.enabled, onCheckedChange = { onToggleTask(item.id, it) })
                            }
                            Text(item.prompt, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { onRunNow(item.id) }) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Text("Run now")
                                }
                                IconButton(onClick = { onDeleteTask(item.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = colors.textMuted)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Explicit GitHub branch/commit/PR publish review dialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitHubPublishDialog(
    sheet: PublishSheetState,
    onDismiss: () -> Unit,
    onConfirmPublish: (headBranch: String, commitMessage: String, prTitle: String) -> Unit
) {
    val colors = LocalKeelColors.current
    var branch by remember(sheet.repository) { mutableStateOf("keel/android-update") }
    var commitMsg by remember(sheet.repository) { mutableStateOf("Update files from Keel Mobile") }
    var prTitle by remember(sheet.repository) { mutableStateOf("Changes from Keel Mobile") }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(modifier = Modifier.fillMaxSize().background(colors.background)) {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background, titleContentColor = colors.textPrimary),
                navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close", tint = colors.textPrimary) } },
                title = { Text("Publish to GitHub") }
            )
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        text = "${sheet.repository} (base: ${sheet.baseBranch} @ ${sheet.baseCommitSha.take(7)})",
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.accent
                    )
                }
                item {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = colors.surface,
                        border = BorderStroke(1.dp, colors.hairline),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Applied changes (${sheet.changes.size})", style = MaterialTheme.typography.labelMedium, color = colors.textMuted)
                            sheet.changes.forEach { c ->
                                Text("• ${c.kind.name.lowercase()}: ${c.path}", style = MaterialTheme.typography.bodySmall, color = colors.textPrimary)
                            }
                        }
                    }
                }
                item {
                    OutlinedTextField(value = branch, onValueChange = { branch = it }, label = { Text("New branch name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                item {
                    OutlinedTextField(value = commitMsg, onValueChange = { commitMsg = it }, label = { Text("Commit message") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                item {
                    OutlinedTextField(value = prTitle, onValueChange = { prTitle = it }, label = { Text("Pull Request title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                sheet.error?.let { err ->
                    item {
                        Text(err, style = MaterialTheme.typography.bodySmall, color = KeelPalette.RustBright)
                    }
                }
                sheet.publishedUrl?.let { url ->
                    item {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = colors.surfaceRaised,
                            border = BorderStroke(1.dp, KeelPalette.Jade),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("Pull Request created:", style = MaterialTheme.typography.labelMedium, color = KeelPalette.JadeBright)
                                Text(url, style = MaterialTheme.typography.bodySmall, color = colors.textPrimary)
                            }
                        }
                    }
                }
                item {
                    Button(
                        onClick = { onConfirmPublish(branch, commitMsg, prTitle) },
                        enabled = !sheet.isPublishing && sheet.publishedUrl == null,
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (sheet.isPublishing) "Publishing branch & PR…" else "Publish branch & open Pull Request",
                            color = colors.background
                        )
                    }
                }
            }
        }
    }
}
