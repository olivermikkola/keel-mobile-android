package com.keelmobile.android

import android.Manifest
import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.StatFs
import android.provider.OpenableColumns
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.keelmobile.android.data.AndroidKeystoreCredentialStore
import com.keelmobile.android.data.InferenceForegroundService
import com.keelmobile.android.data.LocalFolderBridge
import com.keelmobile.android.data.ModelDownloadService
import com.keelmobile.android.data.ModelLibraryManager
import com.keelmobile.android.data.PhotoAttachmentHelper
import com.keelmobile.android.data.RecallStore
import com.keelmobile.android.data.ScheduledTaskStore
import com.keelmobile.android.data.ScheduledTaskWorker
import com.keelmobile.android.data.TaskStore
import com.keelmobile.android.inference.AndroidLocalInference
import com.keelmobile.android.ui.KeelAppRoot
import com.keelmobile.android.ui.KeelController
import com.keelmobile.android.ui.PreviewPayload
import com.keelmobile.android.ui.theme.KeelTheme
import com.keelmobile.harness.AppPreset
import com.keelmobile.harness.ThermalLevel
import java.io.File
import java.util.Locale

class KeelApplication : Application() {
    lateinit var controller: KeelController
        private set

    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    override fun onCreate() {
        super.onCreate()
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)
        val totalRam = if (memInfo.totalMem > 0L) memInfo.totalMem else 8L * 1024 * 1024 * 1024
        val availRam = if (memInfo.availMem > 0L) memInfo.availMem else totalRam / 2
        val freeStorage = runCatching { StatFs(filesDir.absolutePath).availableBytes }.getOrDefault(32L * 1024 * 1024 * 1024)

        val storeDir = File(filesDir, "keel-store")
        val taskStore = TaskStore(storeDir)
        val scheduledStore = ScheduledTaskStore(storeDir)
        val modelLibrary = ModelLibraryManager(
            modelsDir = File(filesDir, "models"),
            onDownloadProgressNotification = { name, pct, done ->
                ModelDownloadService.update(this, name, pct, done)
            }
        )
        val credentials = AndroidKeystoreCredentialStore(this)
        val inference = AndroidLocalInference()
        val folderBridge = LocalFolderBridge(this)
        val recallStore = RecallStore(storeDir, modelLibrary, taskStore)

        controller = KeelController(
            taskStore = taskStore,
            modelLibrary = modelLibrary,
            credentials = credentials,
            engine = inference,
            totalRamBytes = totalRam,
            availableRamBytes = availRam,
            freeStorageBytes = freeStorage,
            scheduledStore = scheduledStore,
            onSyncSchedule = { task -> scheduledStore.syncWithWorkManager(this, task) },
            onCancelSchedule = { id -> scheduledStore.cancelInWorkManager(this, id) },
            localFolderBridge = folderBridge,
            recallStore = recallStore,
            onInferenceForegroundStatus = { modelName, status, running ->
                InferenceForegroundService.update(this, modelName, status, running)
            }
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            val listener = PowerManager.OnThermalStatusChangedListener { status ->
                val level = when (status) {
                    PowerManager.THERMAL_STATUS_SEVERE -> ThermalLevel.SERIOUS
                    PowerManager.THERMAL_STATUS_CRITICAL,
                    PowerManager.THERMAL_STATUS_EMERGENCY,
                    PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalLevel.CRITICAL
                    PowerManager.THERMAL_STATUS_MODERATE -> ThermalLevel.FAIR
                    else -> ThermalLevel.NOMINAL
                }
                controller.onThermalStatusChanged(level)
            }
            thermalListener = listener
            runCatching { pm?.addThermalStatusListener(listener) }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            controller.onMemoryPressure()
        }
    }
}

class MainActivity : ComponentActivity() {
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingCameraUri: Uri? = null

    private val controller: KeelController
        get() = (application as KeelApplication).controller

    private val attachFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) handleAttachUri(uri)
    }

    private val openFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
            controller.openLocalFolder(uri)
        }
    }

    private val pickPhotoLauncher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) handlePhotoUri(uri)
    }

    private val takePhotoLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = pendingCameraUri
        pendingCameraUri = null
        if (ok && uri != null) handlePhotoUri(uri)
    }

    private val importModelLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) handleImportModelUri(uri)
    }

    private val dictateLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val spoken = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) {
            controller.sendMessage(spoken)
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Foreground notifications work silently if granted; app remains functional if denied.
    }

    private val audioPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchOfflineDictation()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                ttsReady = true
            }
        }

        handleIncomingIntent(intent)

        setContent {
            KeelTheme {
                KeelAppRoot(
                    controller = controller,
                    onAttachFileClick = {
                        attachFileLauncher.launch(arrayOf("text/*", "application/json", "application/xml", "*/*"))
                    },
                    onOpenFolderClick = {
                        openFolderLauncher.launch(null)
                    },
                    onAttachPhotoClick = {
                        pickPhotoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onTakePhotoClick = {
                        launchCameraCapture()
                    },
                    onImportModelClick = {
                        importModelLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                    },
                    onDictateClick = {
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            launchOfflineDictation()
                        } else {
                            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onSpeakText = { text ->
                        if (ttsReady) {
                            tts?.speak(text.take(3_900), TextToSpeech.QUEUE_FLUSH, null, "keel-reply")
                        }
                    },
                    onSharePreview = { payload -> sharePreviewFile(payload) }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            "com.keelmobile.android.ACTION_NEW_CHAT" -> controller.startNewChat(null)
            "com.keelmobile.android.ACTION_OPEN_STUDY" -> controller.startNewChat(AppPreset.STUDY)
            "com.keelmobile.android.ACTION_OPEN_WEBSITES" -> controller.startNewChat(AppPreset.WEBSITES)
            ScheduledTaskWorker.ACTION_OPEN_TASK -> {
                intent.getStringExtra(ScheduledTaskWorker.EXTRA_TASK_ID)?.let { controller.selectTask(it) }
            }
            Intent.ACTION_SEND -> {
                val type = intent.type.orEmpty()
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                @Suppress("DEPRECATION")
                val streamUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                when {
                    type.startsWith("image/") && streamUri != null -> handlePhotoUri(streamUri)
                    streamUri != null -> handleAttachUri(streamUri)
                    !sharedText.isNullOrBlank() -> controller.receiveSharedText(sharedText)
                }
            }
        }
    }

    private fun launchCameraCapture() {
        val dir = File(cacheDir, "camera").apply { mkdirs() }
        val photoFile = File(dir, "capture-${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", photoFile)
        pendingCameraUri = uri
        runCatching { takePhotoLauncher.launch(uri) }
    }

    private fun handlePhotoUri(uri: Uri) {
        val img = PhotoAttachmentHelper.decodeToVisionImage(this, uri) ?: return
        controller.attachPhoto(img)
    }

    private fun handleAttachUri(uri: Uri) {
        val name = queryDisplayName(uri) ?: "attached.txt"
        val content = runCatching {
            contentResolver.openInputStream(uri)?.use { stream ->
                readBoundedBytes(stream, 512 * 1024).toString(Charsets.UTF_8)
            }
        }.getOrNull() ?: return
        controller.attachFile(name, content)
    }

    private fun readBoundedBytes(stream: java.io.InputStream, maxBytes: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var remaining = maxBytes
        while (remaining > 0) {
            val read = stream.read(buf, 0, minOf(buf.size, remaining))
            if (read <= 0) break
            out.write(buf, 0, read)
            remaining -= read
        }
        return out.toByteArray()
    }

    private fun handleImportModelUri(uri: Uri) {
        val name = queryDisplayName(uri) ?: "custom.gguf"
        controller.importLocalModel(name) {
            contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Could not open selected file.")
        }
    }

    private fun launchOfflineDictation() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to Keel (offline)")
        }
        runCatching { dictateLauncher.launch(intent) }
    }

    private fun sharePreviewFile(payload: PreviewPayload) {
        val dir = File(cacheDir, "exports").apply { mkdirs() }
        val outFile = File(dir, payload.title.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        outFile.writeText(payload.rawText)
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", outFile)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share ${payload.title}"))
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return uri.lastPathSegment
    }
}
