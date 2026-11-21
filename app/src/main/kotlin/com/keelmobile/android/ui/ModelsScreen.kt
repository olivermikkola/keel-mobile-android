package com.keelmobile.android.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.keelmobile.android.ui.theme.KeelPalette
import com.keelmobile.android.ui.theme.LocalKeelColors
import com.keelmobile.harness.DeviceMemoryGate
import com.keelmobile.harness.LocalModel
import com.keelmobile.harness.MemoryFit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(
    state: KeelUiState,
    onBack: () -> Unit,
    onSelectModel: (String) -> Unit,
    onDownloadModel: (LocalModel) -> Unit,
    onPauseDownload: () -> Unit,
    onDiscardPartial: (LocalModel) -> Unit,
    onDeleteModel: (LocalModel) -> Unit,
    onImportLocalFile: () -> Unit,
    onImportHfLink: (String) -> Unit
) {
    val colors = LocalKeelColors.current
    val ms = state.modelState
    var hfUrl by remember { mutableStateOf("") }
    val ramGb = state.deviceTotalRamBytes / (1024.0 * 1024.0 * 1024.0)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = colors.background,
                titleContentColor = colors.textPrimary
            ),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.textPrimary)
                }
            },
            title = { Text("Models") }
        )

        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = colors.surfaceRaised,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "Device memory: ${"%.1f".format(ramGb)} GB RAM",
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.accent,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "All models run locally on this Android device through llama.cpp. Every pinned download is SHA-256 verified before activation.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textMuted
                        )
                    }
                }
            }

            ms.error?.let { err ->
                item {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = KeelPalette.RustDeep.copy(alpha = 0.25f),
                        border = BorderStroke(1.dp, KeelPalette.Rust),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(err, color = colors.textPrimary, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            item {
                Text("Chat & Agent Models", style = MaterialTheme.typography.titleSmall, color = colors.textMuted)
            }

            items(ms.allChatModels, key = { it.id }) { model ->
                ModelRowCard(
                    model = model,
                    installed = model.id in ms.installedIds,
                    selected = model.id == ms.selectedModelId,
                    isDownloading = ms.activeDownloadId == model.id,
                    downloadProgress = ms.downloadProgress,
                    verifying = ms.verifying && ms.activeDownloadId == model.id,
                    partialBytes = ms.partialBytes[model.id] ?: 0L,
                    fit = DeviceMemoryGate.evaluate(model, state.deviceTotalRamBytes),
                    onSelect = { onSelectModel(model.id) },
                    onDownload = { onDownloadModel(model) },
                    onPause = onPauseDownload,
                    onDiscard = { onDiscardPartial(model) },
                    onDelete = { onDeleteModel(model) }
                )
            }

            item {
                Spacer(Modifier.height(6.dp))
                Text("Utility Models", style = MaterialTheme.typography.titleSmall, color = colors.textMuted)
            }

            items(ms.utilities, key = { it.id }) { model ->
                ModelRowCard(
                    model = model,
                    installed = model.id in ms.installedIds,
                    selected = false,
                    isDownloading = ms.activeDownloadId == model.id,
                    downloadProgress = ms.downloadProgress,
                    verifying = ms.verifying && ms.activeDownloadId == model.id,
                    partialBytes = ms.partialBytes[model.id] ?: 0L,
                    fit = DeviceMemoryGate.evaluate(model, state.deviceTotalRamBytes),
                    onSelect = {},
                    onDownload = { onDownloadModel(model) },
                    onPause = onPauseDownload,
                    onDiscard = { onDiscardPartial(model) },
                    onDelete = { onDeleteModel(model) }
                )
            }

            item {
                Spacer(Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = colors.surface,
                    border = BorderStroke(1.dp, colors.hairline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Import custom GGUF", style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
                        OutlinedButton(onClick = onImportLocalFile, modifier = Modifier.fillMaxWidth()) {
                            Text("Import .gguf file from device storage")
                        }
                        OutlinedTextField(
                            value = hfUrl,
                            onValueChange = { hfUrl = it },
                            label = { Text("Hugging Face .gguf link") },
                            placeholder = { Text("https://huggingface.co/.../model.gguf") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {
                                onImportHfLink(hfUrl)
                                hfUrl = ""
                            },
                            enabled = hfUrl.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
                        ) {
                            Text("Download from link", color = colors.background)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelRowCard(
    model: LocalModel,
    installed: Boolean,
    selected: Boolean,
    isDownloading: Boolean,
    downloadProgress: Float,
    verifying: Boolean,
    partialBytes: Long,
    fit: MemoryFit,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onDiscard: () -> Unit,
    onDelete: () -> Unit
) {
    val colors = LocalKeelColors.current
    val sizeGb = model.downloadBytes / 1_000_000_000.0
    val fitLabel = when (fit) {
        MemoryFit.COMFORTABLE -> "Fits comfortably"
        MemoryFit.TIGHT -> "Tight memory fit"
        MemoryFit.TOO_LARGE -> "May exceed RAM"
    }
    val fitColor = when (fit) {
        MemoryFit.COMFORTABLE -> KeelPalette.Jade
        MemoryFit.TIGHT -> KeelPalette.RustBright
        MemoryFit.TOO_LARGE -> KeelPalette.Rust
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = colors.surface,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) colors.accent else colors.hairline),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("model_card_${model.id}")
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = model.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${"%.2f".format(sizeGb)} GB · ${model.quantization}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textMuted
                )
            }
            Text(text = model.detail, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
            Text(text = fitLabel, style = MaterialTheme.typography.labelSmall, color = fitColor)

            if (isDownloading) {
                LinearProgressIndicator(
                    progress = { downloadProgress },
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.accent
                )
                Text(
                    text = if (verifying) "Verifying SHA-256…" else "${(downloadProgress * 100).toInt()}% downloaded",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.accent
                )
            }

            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    installed -> {
                        if (!model.isUtility) {
                            Button(
                                onClick = onSelect,
                                enabled = !selected,
                                colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
                            ) {
                                Text(if (selected) "Selected" else "Use model", color = colors.background)
                            }
                        }
                        OutlinedButton(onClick = onDelete) {
                            Text("Delete")
                        }
                    }
                    isDownloading -> {
                        OutlinedButton(onClick = onPause) {
                            Text("Pause")
                        }
                    }
                    partialBytes > 0L -> {
                        Button(onClick = onDownload, colors = ButtonDefaults.buttonColors(containerColor = colors.accent)) {
                            Text("Resume (${partialBytes / (1024 * 1024)} MB)", color = colors.background)
                        }
                        OutlinedButton(onClick = onDiscard) {
                            Text("Discard")
                        }
                    }
                    else -> {
                        Button(onClick = onDownload, colors = ButtonDefaults.buttonColors(containerColor = colors.accent)) {
                            Text("Download", color = colors.background)
                        }
                    }
                }
            }
        }
    }
}
