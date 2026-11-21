package com.keelmobile.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keelmobile.android.data.SavedMessage
import com.keelmobile.android.ui.theme.KeelMark
import com.keelmobile.android.ui.theme.KeelPalette
import com.keelmobile.android.ui.theme.LocalKeelColors
import com.keelmobile.harness.ChatBlockParser
import com.keelmobile.harness.ChatMode
import com.keelmobile.harness.MessageSegment
import com.keelmobile.harness.PendingApproval
import com.keelmobile.harness.ProseBlock
import com.keelmobile.harness.ProposedEdit

private val GENERAL_STARTERS = listOf(
    "Attach a CSV and total expenses by category",
    "Make a clean one-page website for a local bakery",
    "Quiz me with 5 flashcards on Android architecture"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: KeelUiState,
    onOpenDrawer: () -> Unit,
    onOpenModels: () -> Unit,
    onNewChat: () -> Unit,
    onSelectMode: (ChatMode) -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onToggleThinking: () -> Unit,
    onAttachFileClick: () -> Unit,
    onDictateClick: () -> Unit,
    onSpeakText: (String) -> Unit,
    onApplyProposal: (String) -> Unit,
    onDiscardProposal: (String) -> Unit,
    onPreviewProposal: (ProposedEdit) -> Unit,
    onResolveApproval: (Boolean) -> Unit,
    onDismissBanner: () -> Unit
) {
    val colors = LocalKeelColors.current
    val task = state.activeTask
    var composerText by remember { mutableStateOf("") }
    var plusMenuExpanded by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val selectedModel = state.modelState.selectedModel
    val modelInstalled = selectedModel.id in state.modelState.installedIds

    LaunchedEffect(task.messages.size, state.streamingReply.length, task.pendingProposals.size) {
        val count = task.messages.size + (if (state.isGenerating) 1 else 0) + task.pendingProposals.size
        if (count > 0) {
            listState.animateScrollToItem(maxOf(0, count - 1))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .imePadding()
    ) {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = colors.background,
                titleContentColor = colors.textPrimary
            ),
            navigationIcon = {
                IconButton(onClick = onOpenDrawer, modifier = Modifier.testTag("drawer_button")) {
                    Icon(Icons.Default.Menu, contentDescription = "Open menu", tint = colors.textPrimary)
                }
            },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        onClick = onOpenModels,
                        shape = RoundedCornerShape(20.dp),
                        color = colors.surface,
                        border = BorderStroke(1.dp, colors.hairline),
                        modifier = Modifier.testTag("model_chip")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (modelInstalled) KeelPalette.Jade else KeelPalette.Rust)
                            )
                            Text(
                                text = selectedModel.name,
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (task.appPreset == null && task.repositoryName == null) {
                        ModeChip(
                            mode = task.mode,
                            onToggle = {
                                onSelectMode(if (task.mode == ChatMode.WORK) ChatMode.CHAT else ChatMode.WORK)
                            }
                        )
                    } else {
                        Text(
                            text = task.appPreset?.title ?: task.repositoryName.orEmpty(),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.accent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            },
            actions = {
                IconButton(onClick = onNewChat, modifier = Modifier.testTag("new_chat_button")) {
                    Icon(Icons.Default.Edit, contentDescription = "New chat", tint = colors.textPrimary)
                }
            }
        )

        state.bannerMessage?.let { msg ->
            Surface(
                color = colors.surfaceRaised,
                border = BorderStroke(1.dp, colors.detail.copy(alpha = 0.45f)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismissBanner, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = colors.textMuted)
                    }
                }
            }
        }

        if (task.messages.isEmpty() && !state.isGenerating) {
            EmptyChatWelcome(
                preset = task.appPreset,
                modelName = selectedModel.name,
                modelInstalled = modelInstalled,
                onOpenModels = onOpenModels,
                onStarterClick = { starter ->
                    if (modelInstalled) onSend(starter) else composerText = starter
                },
                modifier = Modifier.weight(1f)
            )
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(task.messages, key = { it.id }) { msg ->
                    ChatMessageRow(message = msg, onSpeak = onSpeakText)
                }

                if (state.isGenerating) {
                    item(key = "live_stream") {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (state.liveSteps.isNotEmpty()) {
                                StepActivityRail(state.liveSteps.map { it.label })
                            }
                            Text(
                                text = state.streamingReply.ifBlank { "Working locally…" },
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.textPrimary
                            )
                        }
                    }
                }

                items(task.pendingProposals, key = { it.id }) { edit ->
                    ProposalReviewCard(
                        edit = edit,
                        onApply = { onApplyProposal(edit.id) },
                        onDiscard = { onDiscardProposal(edit.id) },
                        onPreview = { onPreviewProposal(edit) }
                    )
                }

                state.pendingApproval?.let { approval ->
                    item(key = "approval_${approval.id}") {
                        ApprovalPromptCard(
                            approval = approval,
                            onApprove = { onResolveApproval(true) },
                            onDeny = { onResolveApproval(false) }
                        )
                    }
                }
            }
        }

        // Composer bar
        Surface(
            color = colors.surface,
            border = BorderStroke(1.dp, colors.hairline),
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box {
                    IconButton(onClick = { plusMenuExpanded = true }, modifier = Modifier.testTag("plus_menu_button")) {
                        Icon(Icons.Default.Add, contentDescription = "More actions", tint = colors.accent)
                    }
                    DropdownMenu(
                        expanded = plusMenuExpanded,
                        onDismissRequest = { plusMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Attach file") },
                            leadingIcon = { Icon(Icons.Default.AttachFile, contentDescription = null) },
                            onClick = {
                                plusMenuExpanded = false
                                onAttachFileClick()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(if (state.thinkingEnabled) "Thinking: On (capped)" else "Thinking: Off") },
                            leadingIcon = { Icon(Icons.Default.Psychology, contentDescription = null) },
                            onClick = {
                                plusMenuExpanded = false
                                onToggleThinking()
                            }
                        )
                    }
                }

                OutlinedTextField(
                    value = composerText,
                    onValueChange = { composerText = it },
                    placeholder = {
                        Text(
                            text = if (state.thinkingEnabled) "Message Keel (Thinking on)…" else "Ask Keel anything…",
                            color = colors.textMuted
                        )
                    },
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (composerText.isNotBlank() && !state.isGenerating) {
                                val toSend = composerText
                                composerText = ""
                                onSend(toSend)
                            }
                        }
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.accent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("composer_input")
                )

                if (state.isGenerating) {
                    Button(
                        onClick = onStop,
                        colors = ButtonDefaults.buttonColors(containerColor = KeelPalette.Rust),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                        modifier = Modifier.testTag("stop_button")
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = "Stop", tint = Color.White)
                        Spacer(Modifier.width(4.dp))
                        Text("Stop", color = Color.White)
                    }
                } else if (composerText.isBlank()) {
                    IconButton(onClick = onDictateClick, modifier = Modifier.testTag("dictate_button")) {
                        Icon(Icons.Default.Mic, contentDescription = "Dictate offline", tint = colors.accent)
                    }
                } else {
                    FilledIconButton(
                        onClick = {
                            val toSend = composerText
                            composerText = ""
                            onSend(toSend)
                        },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = colors.accent,
                            contentColor = colors.background
                        ),
                        modifier = Modifier.testTag("send_button")
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeChip(mode: ChatMode, onToggle: () -> Unit) {
    val colors = LocalKeelColors.current
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(16.dp),
        color = colors.surfaceRaised,
        modifier = Modifier.testTag("mode_toggle")
    ) {
        Text(
            text = if (mode == ChatMode.WORK) "Work" else "Chat",
            style = MaterialTheme.typography.labelMedium,
            color = colors.accent,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun EmptyChatWelcome(
    preset: com.keelmobile.harness.AppPreset?,
    modelName: String,
    modelInstalled: Boolean,
    onOpenModels: () -> Unit,
    onStarterClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalKeelColors.current
    val starters = preset?.starters ?: GENERAL_STARTERS

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        KeelMark(modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(14.dp))
        Text(
            text = preset?.title ?: "Keel Mobile",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = preset?.summary ?: "Your local AI agent on this device. No cloud fallback.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textMuted
        )

        if (!modelInstalled) {
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onOpenModels,
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                modifier = Modifier.testTag("setup_model_cta")
            ) {
                Text("Download $modelName", color = colors.background)
            }
        }

        Spacer(Modifier.height(22.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            starters.forEach { starter ->
                Surface(
                    onClick = { onStarterClick(starter) },
                    shape = RoundedCornerShape(16.dp),
                    color = colors.surface,
                    border = BorderStroke(1.dp, colors.hairline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = starter,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textPrimary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatMessageRow(
    message: SavedMessage,
    onSpeak: (String) -> Unit
) {
    val colors = LocalKeelColors.current
    val clipboard = LocalClipboardManager.current

    when (message.role) {
        "user" -> {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = colors.userBubble,
                    modifier = Modifier.widthIn(max = 310.dp)
                ) {
                    SelectionContainer {
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.textPrimary,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                        )
                    }
                }
            }
        }
        "system_notice" -> {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = colors.surfaceRaised,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
        else -> {
            val segments = remember(message.text) { ChatBlockParser.parseSegments(message.text) }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (message.stepLabels.isNotEmpty()) {
                    StepActivityRail(message.stepLabels)
                }
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        segments.forEach { seg ->
                            when (seg) {
                                is MessageSegment.CodeBlock -> CodeBlockCard(
                                    language = seg.language,
                                    code = seg.code,
                                    onCopy = { clipboard.setText(AnnotatedString(seg.code)) }
                                )
                                is MessageSegment.Prose -> ProseBlocksView(seg.blocks)
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(message.text)) }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Copy", style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = { onSpeak(message.text) }) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Listen", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun StepActivityRail(labels: List<String>) {
    val colors = LocalKeelColors.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.hairline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            labels.forEach { label ->
                Text(
                    text = "• $label",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.accent
                )
            }
        }
    }
}

@Composable
private fun ProseBlocksView(blocks: List<ProseBlock>) {
    val colors = LocalKeelColors.current
    blocks.forEach { block ->
        when (block) {
            is ProseBlock.Heading -> Text(
                text = block.text,
                style = when (block.level) {
                    1 -> MaterialTheme.typography.titleLarge
                    2 -> MaterialTheme.typography.titleMedium
                    else -> MaterialTheme.typography.titleSmall
                },
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary
            )
            is ProseBlock.Paragraph -> Text(
                text = block.text,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.textPrimary
            )
            is ProseBlock.BulletItem -> {
                val prefix = when (block.checked) {
                    true -> "☑ "
                    false -> "☐ "
                    null -> "• "
                }
                Text(
                    text = "$prefix${block.text}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.textPrimary,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            is ProseBlock.NumberedItem -> Text(
                text = "${block.number}. ${block.text}",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.textPrimary,
                modifier = Modifier.padding(start = 8.dp)
            )
            is ProseBlock.BlockQuote -> Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surfaceRaised, RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                Text(
                    text = block.text,
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                    color = colors.textMuted
                )
            }
            is ProseBlock.HorizontalRule -> HorizontalDivider(color = colors.hairline)
            is ProseBlock.Table -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .border(1.dp, colors.hairline, RoundedCornerShape(8.dp))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = block.headers.joinToString("  |  "),
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.accent
                    )
                    HorizontalDivider(color = colors.hairline)
                    block.rows.forEach { r ->
                        Text(
                            text = r.joinToString("  |  "),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textPrimary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CodeBlockCard(
    language: String?,
    code: String,
    onCopy: () -> Unit
) {
    val colors = LocalKeelColors.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.hairline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surfaceRaised)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = language ?: "code",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textMuted,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onCopy, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Copy", style = MaterialTheme.typography.labelSmall)
                }
            }
            Text(
                text = code,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = colors.textPrimary,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp)
            )
        }
    }
}

@Composable
private fun ProposalReviewCard(
    edit: ProposedEdit,
    onApply: () -> Unit,
    onDiscard: () -> Unit,
    onPreview: () -> Unit
) {
    val colors = LocalKeelColors.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.accent.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("proposal_card_${edit.path}")
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${edit.operation.name.lowercase().replaceFirstChar { it.uppercase() }} · ${edit.path}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.accent,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onPreview) {
                    Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Preview")
                }
            }
            Surface(
                color = colors.background,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(10.dp)
                ) {
                    edit.diffPreview.lines().take(28).forEach { line ->
                        val lineColor = when {
                            line.startsWith("+") && !line.startsWith("+++") -> KeelPalette.JadeBright
                            line.startsWith("-") && !line.startsWith("---") -> KeelPalette.RustBright
                            else -> colors.textMuted
                        }
                        Text(
                            text = line,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = lineColor
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onApply,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                    modifier = Modifier.testTag("apply_proposal_button")
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = colors.background, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Apply", color = colors.background)
                }
                OutlinedButton(
                    onClick = onDiscard,
                    modifier = Modifier.testTag("discard_proposal_button")
                ) {
                    Text("Discard")
                }
            }
        }
    }
}

@Composable
private fun ApprovalPromptCard(
    approval: PendingApproval,
    onApprove: () -> Unit,
    onDeny: () -> Unit
) {
    val colors = LocalKeelColors.current
    val (title, detail, confirmLabel) = when (approval) {
        is PendingApproval.WebSearchQuery -> Triple("Approve web search?", "Query: \"${approval.query}\"", "Allow search")
        is PendingApproval.RememberFact -> Triple("Save to memory?", approval.fact, "Remember")
        is PendingApproval.McpWriteCall -> Triple("Run connected tool?", "${approval.toolName}: ${approval.arguments}", "Allow")
        is PendingApproval.FileChange -> Triple("Approve file change?", approval.edit.path, "Apply")
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = colors.surfaceRaised,
        border = BorderStroke(1.dp, KeelPalette.Rust),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = colors.detail, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onApprove, colors = ButtonDefaults.buttonColors(containerColor = colors.accent)) {
                    Text(confirmLabel, color = colors.background)
                }
                OutlinedButton(onClick = onDeny) {
                    Text("Decline")
                }
            }
        }
    }
}
