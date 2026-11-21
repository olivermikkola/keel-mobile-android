package com.keelmobile.android

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.OpenableColumns
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.keelmobile.android.data.AndroidKeystoreCredentialStore
import com.keelmobile.android.data.ModelDownloadService
import com.keelmobile.android.data.ModelLibraryManager
import com.keelmobile.android.data.TaskStore
import com.keelmobile.android.inference.AndroidLocalInference
import com.keelmobile.android.ui.KeelAppRoot
import com.keelmobile.android.ui.KeelController
import com.keelmobile.android.ui.PreviewPayload
import com.keelmobile.android.ui.theme.KeelTheme
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

        val taskStore = TaskStore(File(filesDir, "keel-store"))
        val modelLibrary = ModelLibraryManager(
            modelsDir = File(filesDir, "models"),
            onDownloadProgressNotification = { name, pct, done ->
                ModelDownloadService.update(this, name, pct, done)
            }
        )
        val credentials = AndroidKeystoreCredentialStore(this)
        val inference = AndroidLocalInference()

        controller = KeelController(
            taskStore = taskStore,
            modelLibrary = modelLibrary,
            credentials = credentials,
            engine = inference,
            totalRamBytes = totalRam
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
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        ) {
            controller.onMemoryPressure()
        }
    }
}

class MainActivity : ComponentActivity() {
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val controller: KeelController
        get() = (application as KeelApplication).controller

    private val attachFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) handleAttachUri(uri)
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                ttsReady = true
            }
        }

        setContent {
            KeelTheme {
                KeelAppRoot(
                    controller = controller,
                    onAttachFileClick = {
                        attachFileLauncher.launch(arrayOf("text/*", "application/json", "application/xml", "*/*"))
                    },
                    onImportModelClick = {
                        importModelLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                    },
                    onDictateClick = { launchOfflineDictation() },
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

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    private fun handleAttachUri(uri: Uri) {
        val name = queryDisplayName(uri) ?: "attached.txt"
        val content = runCatching {
            contentResolver.openInputStream(uri)?.use { stream ->
                val bytes = stream.readNBytes(512 * 1024)
                bytes.toString(Charsets.UTF_8)
            }
        }.getOrNull() ?: return
        controller.attachFile(name, content)
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
