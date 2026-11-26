package com.keelmobile.android.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.keelmobile.harness.GgufMetadata
import com.keelmobile.harness.HuggingFaceUrl
import com.keelmobile.harness.ImportedModel
import com.keelmobile.harness.LocalModel
import com.keelmobile.harness.ModelAsset
import com.keelmobile.harness.ModelCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

data class ModelLibraryState(
    val catalog: List<LocalModel> = ModelCatalog.catalog,
    val utilities: List<LocalModel> = ModelCatalog.utilities,
    val imported: List<ImportedModel> = emptyList(),
    val installedIds: Set<String> = emptySet(),
    val partialBytes: Map<String, Long> = emptyMap(),
    val selectedModelId: String = "4B",
    val activeDownloadId: String? = null,
    val downloadProgress: Float = 0f,
    val verifying: Boolean = false,
    val error: String? = null
) {
    val allChatModels: List<LocalModel>
        get() = catalog + imported.map { it.toLocalModel() }

    val selectedModel: LocalModel
        get() = allChatModels.firstOrNull { it.id == selectedModelId } ?: catalog.first()
}

/**
 * Manages pinned model downloads (resumable HTTP Range + SHA-256 verification),
 * custom GGUF imports (local Files or Hugging Face links with GGUF header inspection),
 * and disk reclamation.
 */
class ModelLibraryManager(
    private val modelsDir: File,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val onDownloadProgressNotification: ((String, Int, Boolean) -> Unit)? = null
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val importedIndexFile = File(modelsDir, "imported-models.json")
    private val selectionFile = File(modelsDir, "selected-model.txt")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null

    private val _state = MutableStateFlow(ModelLibraryState())
    val state: StateFlow<ModelLibraryState> = _state.asStateFlow()

    init {
        modelsDir.mkdirs()
        refresh()
    }

    fun refresh() {
        val imported = loadImportedModels()
        val all = ModelCatalog.catalog + ModelCatalog.utilities + imported.map { it.toLocalModel() }
        val installed = mutableSetOf<String>()
        val partials = mutableMapOf<String, Long>()

        for (model in all) {
            val allPresent = model.assets.all { assetFile(it).exists() && assetFile(it).length() > 0L }
            if (allPresent) {
                installed += model.id
            } else {
                val pBytes = model.assets.sumOf { asset ->
                    val fin = assetFile(asset)
                    val part = partialFile(asset)
                    when {
                        fin.exists() -> fin.length()
                        part.exists() -> part.length()
                        else -> 0L
                    }
                }
                if (pBytes > 0L) partials[model.id] = pBytes
            }
        }

        val savedSelected = runCatching { selectionFile.takeIf { it.exists() }?.readText()?.trim() }.getOrNull()
        val validSelection = savedSelected?.takeIf { id -> all.any { it.id == id && !it.isUtility } } ?: "4B"

        _state.update {
            it.copy(
                imported = imported,
                installedIds = installed,
                partialBytes = partials,
                selectedModelId = validSelection
            )
        }
    }

    fun selectModel(id: String) {
        val valid = _state.value.allChatModels.any { it.id == id }
        if (!valid) return
        selectionFile.writeText(id)
        _state.update { it.copy(selectedModelId = id) }
    }

    fun modelFilePath(model: LocalModel): File = File(modelsDir, model.fileName)

    fun assetFile(asset: ModelAsset): File = File(modelsDir, asset.fileName)

    fun partialFile(asset: ModelAsset): File = File(modelsDir, "${asset.fileName}.partial")

    fun startDownload(model: LocalModel) {
        if (_state.value.activeDownloadId != null) return
        _state.update { it.copy(activeDownloadId = model.id, downloadProgress = 0f, verifying = false, error = null) }
        downloadJob = scope.launch {
            try {
                downloadModelAssets(model)
                if (model.id.startsWith("hf-")) {
                    registerDownloadedHfModel(model)
                }
                refresh()
                _state.update { it.copy(activeDownloadId = null, downloadProgress = 0f, verifying = false, error = null) }
                onDownloadProgressNotification?.invoke(model.name, 100, true)
            } catch (_: CancellationException) {
                refresh()
                _state.update { it.copy(activeDownloadId = null, verifying = false) }
                onDownloadProgressNotification?.invoke(model.name, 0, true)
            } catch (e: Exception) {
                refresh()
                _state.update {
                    it.copy(
                        activeDownloadId = null,
                        verifying = false,
                        error = e.message ?: "Model download failed."
                    )
                }
                onDownloadProgressNotification?.invoke(model.name, 0, true)
            }
        }
    }

    private fun registerDownloadedHfModel(model: LocalModel) {
        val file = modelFilePath(model)
        if (!file.exists()) return
        val header = ByteArray(GgufMetadata.MAX_HEADER_BYTES)
        val headerRead = FileInputStream(file).use { it.read(header) }.coerceAtLeast(0)
        val meta = GgufMetadata.parse(header.copyOf(headerRead))
        val arch = meta?.toArchitecture()
        val existing = _state.value.imported.filterNot { it.id == model.id || it.fileName == model.fileName }
        val imported = ImportedModel(
            id = model.id,
            name = meta?.name?.take(48) ?: model.name,
            fileName = model.fileName,
            bytes = file.length(),
            sha256 = "",
            architectureName = meta?.architecture ?: "gguf",
            family = meta?.inferFamily() ?: model.family,
            contextSize = (meta?.contextLength ?: model.contextSize).coerceIn(2048, 32768),
            layerCount = arch?.layerCount ?: model.architecture.layerCount,
            attentionLayerCount = arch?.fullAttentionLayerCount ?: model.architecture.fullAttentionLayerCount,
            kvHeadCount = arch?.kvHeadCount ?: model.architecture.kvHeadCount,
            keyLength = arch?.keyLength ?: model.architecture.keyLength,
            valueLength = arch?.valueLength ?: model.architecture.valueLength
        )
        saveImportedModels(existing + imported)
    }

    fun pauseDownload() {
        downloadJob?.cancel()
        downloadJob = null
    }

    fun discardPartial(model: LocalModel) {
        if (_state.value.activeDownloadId == model.id) pauseDownload()
        for (asset in model.assets) {
            partialFile(asset).delete()
        }
        refresh()
    }

    fun deleteInstalled(model: LocalModel) {
        if (_state.value.activeDownloadId == model.id) pauseDownload()
        for (asset in model.assets) {
            assetFile(asset).delete()
            partialFile(asset).delete()
        }
        if (model.isImported) {
            val remaining = _state.value.imported.filterNot { it.id == model.id }
            saveImportedModels(remaining)
        }
        refresh()
    }

    /**
     * Imports a local GGUF file stream (e.g. from Android Storage Access Framework),
     * inspecting its GGUF header (<= 256 KB), computing SHA-256, and copying into private storage.
     */
    suspend fun importLocalStream(displayName: String, inputProvider: () -> InputStream): LocalModel =
        withContext(Dispatchers.IO) {
            val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                .let { if (it.endsWith(".gguf", ignoreCase = true)) it else "$it.gguf" }
            val dest = File(modelsDir, "imported-${UUID.randomUUID().toString().take(8)}-$safeName")
            val partial = File(modelsDir, "${dest.name}.partial")

            val headerBuf = ByteArray(GgufMetadata.MAX_HEADER_BYTES)
            var headerFilled = 0
            val digest = MessageDigest.getInstance("SHA-256")
            var totalBytes = 0L

            try {
                inputProvider().use { input ->
                    FileOutputStream(partial).use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            if (headerFilled < headerBuf.size) {
                                val copy = minOf(n, headerBuf.size - headerFilled)
                                System.arraycopy(buf, 0, headerBuf, headerFilled, copy)
                                headerFilled += copy
                            }
                            digest.update(buf, 0, n)
                            output.write(buf, 0, n)
                            totalBytes += n
                        }
                    }
                }
                val meta = GgufMetadata.parse(headerBuf.copyOf(headerFilled))
                    ?: throw IllegalArgumentException("This file is not a valid GGUF v2/v3 model.")
                if (!meta.isSupportedArchitecture) {
                    throw IllegalArgumentException("Unsupported GGUF architecture: ${meta.architecture ?: "unknown"}.")
                }
                val sha = digest.digest().joinToString("") { "%02x".format(it) }
                if (!partial.renameTo(dest)) {
                    partial.copyTo(dest, overwrite = true)
                    partial.delete()
                }
                val arch = meta.toArchitecture()!!
                val imported = ImportedModel(
                    id = "imp-${sha.take(10)}",
                    name = meta.name?.take(48) ?: safeName.removeSuffix(".gguf"),
                    fileName = dest.name,
                    bytes = totalBytes,
                    sha256 = sha,
                    architectureName = meta.architecture ?: "gguf",
                    family = meta.inferFamily(),
                    contextSize = (meta.contextLength ?: 8192).coerceIn(2048, 32768),
                    layerCount = arch.layerCount,
                    attentionLayerCount = arch.fullAttentionLayerCount,
                    kvHeadCount = arch.kvHeadCount,
                    keyLength = arch.keyLength,
                    valueLength = arch.valueLength
                )
                saveImportedModels(_state.value.imported + imported)
                refresh()
                imported.toLocalModel()
            } catch (e: Exception) {
                partial.delete()
                throw e
            }
        }

    /**
     * Validates a pasted Hugging Face .gguf link and starts downloading it as a custom model.
     */
    fun importFromHuggingFaceLink(url: String): LocalModel {
        val parsed = HuggingFaceUrl.parse(url)
            ?: throw IllegalArgumentException("Paste a direct Hugging Face .gguf link (https://huggingface.co/.../*.gguf).")
        val custom = LocalModel(
            id = "hf-${parsed.fileName.hashCode().toUInt().toString(16)}",
            name = parsed.fileName.removeSuffix(".gguf").take(42),
            bytes = 0L,
            revision = parsed.revision,
            sha256 = "",
            detail = "Custom Hugging Face GGUF (${parsed.repository}). Tool reliability varies.",
            contextSize = 8_192,
            repository = parsed.repository,
            fileName = parsed.fileName,
            sourceUrl = parsed.resolveUrl
        )
        startDownload(custom)
        return custom
    }

    /**
     * Resumable download with Range request handling + SHA-256 verification.
     * Exposed internally so MockWebServer unit tests can verify resume, range-ignore restart,
     * and checksum rejection deterministically!
     */
    suspend fun downloadModelAssets(model: LocalModel) = withContext(Dispatchers.IO) {
        val totalExpected = model.downloadBytes
        var completedPrior = 0L

        for (asset in model.assets) {
            val finalFile = assetFile(asset)
            if (finalFile.exists() && (asset.sha256.isEmpty() || verifySha256(finalFile, asset.sha256))) {
                completedPrior += finalFile.length()
                continue
            }

            val partial = partialFile(asset)
            var existingOffset = if (partial.exists()) partial.length() else 0L

            if (asset.bytes <= 0L || existingOffset < asset.bytes) {
                val reqBuilder = Request.Builder().url(asset.downloadUrl)
                if (existingOffset > 0L) {
                    reqBuilder.header("Range", "bytes=$existingOffset-")
                }

                httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("Download failed with HTTP ${resp.code}.")
                    }
                    // If server ignored Range (200 instead of 206), restart from byte 0 instead of appending duplicates!
                    val append = existingOffset > 0L && resp.code == 206
                    if (!append) {
                        existingOffset = 0L
                    }
                    val body = resp.body ?: throw IllegalStateException("Empty download response.")
                    val contentLen = body.contentLength()
                    val assetTotal = if (asset.bytes > 0L) asset.bytes else existingOffset + maxOf(0L, contentLen)
                    val overallTotal = if (totalExpected > 0L) totalExpected else assetTotal

                    FileOutputStream(partial, append).use { fos ->
                        val buf = ByteArray(64 * 1024)
                        var received = existingOffset
                        var lastNotifyMs = 0L
                        var lastNotifyPct = -1
                        val stream = body.byteStream()
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = stream.read(buf)
                            if (n <= 0) break
                            fos.write(buf, 0, n)
                            received += n
                            val pct = if (overallTotal > 0L) {
                                ((completedPrior + received).toFloat() / overallTotal.toFloat()).coerceIn(0f, 1f)
                            } else 0f
                            _state.update { it.copy(downloadProgress = pct) }
                            val pctInt = (pct * 100).toInt()
                            val nowMs = System.currentTimeMillis()
                            if (pctInt != lastNotifyPct && (nowMs - lastNotifyMs >= 500L || pctInt >= 100)) {
                                lastNotifyMs = nowMs
                                lastNotifyPct = pctInt
                                onDownloadProgressNotification?.invoke(model.name, pctInt, false)
                            }
                        }
                    }
                }
            }

            _state.update { it.copy(verifying = true) }
            if (asset.bytes > 0L && partial.length() != asset.bytes) {
                partial.delete()
                throw IllegalStateException("Download verification failed: expected ${asset.bytes} bytes, got ${partial.length()}. Damaged partial file removed.")
            }
            if (asset.sha256.isNotEmpty() && !verifySha256(partial, asset.sha256)) {
                partial.delete()
                throw IllegalStateException("Download verification failed (SHA-256 mismatch). Damaged download removed; retry for a fresh copy.")
            }
            if (!partial.renameTo(finalFile)) {
                partial.copyTo(finalFile, overwrite = true)
                partial.delete()
            }
            completedPrior += finalFile.length()
        }
    }

    private fun verifySha256(file: File, expectedHex: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = fis.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        return actual.equals(expectedHex, ignoreCase = true)
    }

    private fun loadImportedModels(): List<ImportedModel> {
        if (!importedIndexFile.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<ImportedModel>>(importedIndexFile.readText())
        }.getOrDefault(emptyList())
    }

    private fun saveImportedModels(list: List<ImportedModel>) {
        importedIndexFile.writeText(json.encodeToString(list))
    }

    companion object {
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}

/**
 * Foreground service that displays model download progress in the Android notification
 * shade so downloads survive leaving the app.
 */
class ModelDownloadService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val title = intent?.getStringExtra(EXTRA_MODEL_NAME) ?: "Downloading model"
        val percent = intent?.getIntExtra(EXTRA_PROGRESS, 0) ?: 0
        startForeground(NOTIFICATION_ID, buildNotification(title, percent))
        return START_NOT_STICKY
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(CHANNEL_ID, "Model Downloads", NotificationManager.IMPORTANCE_LOW)
                nm.createNotificationChannel(ch)
            }
        }
    }

    private fun buildNotification(modelName: String, percent: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Keel Mobile · $modelName")
            .setContentText(if (percent in 1..99) "Downloading $percent%" else "Preparing local model…")
            .setProgress(100, percent.coerceIn(0, 100), percent == 0)
            .setOngoing(true)
            .build()

    companion object {
        const val CHANNEL_ID = "keel_model_downloads"
        const val NOTIFICATION_ID = 1001
        const val ACTION_UPDATE = "com.keelmobile.android.DOWNLOAD_UPDATE"
        const val ACTION_STOP = "com.keelmobile.android.DOWNLOAD_STOP"
        const val EXTRA_MODEL_NAME = "model_name"
        const val EXTRA_PROGRESS = "progress"

        fun update(context: Context, modelName: String, percent: Int, done: Boolean) {
            val intent = Intent(context, ModelDownloadService::class.java).apply {
                action = if (done) ACTION_STOP else ACTION_UPDATE
                putExtra(EXTRA_MODEL_NAME, modelName)
                putExtra(EXTRA_PROGRESS, percent)
            }
            runCatching {
                if (done) context.startService(intent)
                else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            }
        }
    }
}
