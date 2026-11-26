package com.keelmobile.android.inference

import com.keelmobile.harness.InferenceCancellation
import com.keelmobile.harness.InferenceTelemetry
import com.keelmobile.harness.LocalGenerationRequest
import com.keelmobile.harness.LocalInferenceEngine
import com.keelmobile.harness.LocalModel
import com.keelmobile.harness.ModelFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

object LlamaNative {
    @Volatile
    var isAvailable: Boolean = false
        private set

    init {
        isAvailable = try {
            System.loadLibrary("keel_llama")
            nativeInit()
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    interface TokenCallback {
        fun onToken(token: String)
    }

    external fun nativeInit()
    external fun nativeHasGpuBackend(): Boolean
    external fun nativeSystemInfo(): String
    external fun nativeLoadModel(
        path: String,
        projectorPath: String?,
        contextSize: Int,
        nThreads: Int,
        quantizeKvQ4: Boolean,
        useGpu: Boolean
    ): Long
    external fun nativeIsSessionGpu(handle: Long): Boolean
    external fun nativeUnloadModel(handle: Long)
    external fun nativeCancel(handle: Long)
    external fun nativeGenerate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        addSpecial: Boolean,
        temp: Float,
        topK: Int,
        topP: Float,
        minP: Float,
        repeatPenalty: Float,
        greedy: Boolean,
        grammar: String?,
        triggerPatterns: Array<String>?,
        callback: TokenCallback
    ): LongArray

    external fun nativeGenerateVision(
        handle: Long,
        prompt: String,
        widths: IntArray,
        heights: IntArray,
        rgbArrays: Array<ByteArray>,
        maxTokens: Int,
        addSpecial: Boolean,
        temp: Float,
        topK: Int,
        topP: Float,
        minP: Float,
        repeatPenalty: Float,
        greedy: Boolean,
        grammar: String?,
        triggerPatterns: Array<String>?,
        callback: TokenCallback
    ): LongArray

    external fun nativeLoadEmbeddingModel(path: String, nThreads: Int): Long
    external fun nativeUnloadEmbeddingModel(handle: Long)
    external fun nativeEmbed(handle: Long, text: String): FloatArray?

    fun safeHasGpuBackend(): Boolean =
        if (isAvailable) runCatching { nativeHasGpuBackend() }.getOrDefault(false) else false

    fun safeSystemInfo(): String =
        if (isAvailable) runCatching { nativeSystemInfo() }.getOrDefault("llama.cpp (unavailable)")
        else "llama.cpp native library not loaded (JVM/test environment)"
}

class LocalModelException(message: String) : Exception(message)

data class BenchmarkSummary(
    val modelName: String,
    val backend: String,
    val prefillTokensPerSec: Double,
    val decodeTokensPerSec: Double,
    val prefillMs: Long,
    val decodeMs: Long
)

/**
 * Single-model on-device llama.cpp runner with:
 * - Vulkan GPU offload + automatic CPU fallback
 * - Multimodal vision (mtmd) for LFM3 VL 2B
 * - Q4_0 KV cache for Bonsai 2 and append-only prefix caching
 * - Native embeddings support for Recall (EmbeddingGemma 300M)
 * - Immediate unload on memory/thermal pressure
 */
class AndroidLocalInference(
    private val availableThreads: Int = preferredCpuThreads()
) : LocalInferenceEngine {

    private val mutex = Mutex()
    private val handleLock = Any()
    @Volatile
    var preferVulkanGpu: Boolean = true
    @Volatile
    private var handle: Long = 0L
    private var loadedPath: String? = null
    private var loadedProjectorPath: String? = null
    private var loadedContextSize: Int = 0
    private var loadedQuantizedKv: Boolean = false
    private var loadedGpuPreference: Boolean = false

    fun cancelActive() {
        synchronized(handleLock) {
            val h = handle
            if (h != 0L && LlamaNative.isAvailable) {
                LlamaNative.nativeCancel(h)
            }
        }
    }

    suspend fun unload() = mutex.withLock {
        withContext(Dispatchers.IO) {
            val h = synchronized(handleLock) {
                val cur = handle
                handle = 0L
                cur
            }
            loadedPath = null
            loadedProjectorPath = null
            loadedContextSize = 0
            if (h != 0L && LlamaNative.isAvailable) {
                LlamaNative.nativeUnloadModel(h)
            }
        }
    }

    suspend fun benchmarkModel(model: LocalModel, modelPath: String): BenchmarkSummary {
        val prompt = com.keelmobile.harness.ModelPrompt.render(
            family = model.family,
            system = "You are a concise assistant.",
            messages = listOf(
                com.keelmobile.harness.PromptMessage(
                    "user",
                    "Count carefully and summarize the key properties of on-device inference on Android phones with Vulkan GPU acceleration. ".repeat(6)
                )
            ),
            thinking = false
        )
        val telemetry = generate(
            request = LocalGenerationRequest(
                model = model,
                modelPath = modelPath,
                prompt = prompt,
                maxTokens = 32,
                greedy = true
            ),
            cancellation = InferenceCancellation(),
            onToken = {}
        )
        val prefillTps = if (telemetry.prefillMs > 0) telemetry.promptTokens * 1000.0 / telemetry.prefillMs else 0.0
        return BenchmarkSummary(
            modelName = model.name,
            backend = telemetry.backend,
            prefillTokensPerSec = prefillTps,
            decodeTokensPerSec = telemetry.tokensPerSecond,
            prefillMs = telemetry.prefillMs,
            decodeMs = telemetry.decodeMs
        )
    }

    override suspend fun generate(
        request: LocalGenerationRequest,
        cancellation: InferenceCancellation,
        onToken: (String) -> Unit
    ): InferenceTelemetry = mutex.withLock {
        if (!LlamaNative.isAvailable) {
            throw LocalModelException("Native llama.cpp runtime is not loaded on this device.")
        }
        val file = File(request.modelPath)
        if (!file.exists() || !file.isFile) {
            throw LocalModelException("Model file not found. Download or select an installed model in Models.")
        }
        val wantProjector = if (request.images.isNotEmpty()) request.projectorPath else null
        if (request.images.isNotEmpty() && (wantProjector.isNullOrBlank() || !File(wantProjector).exists())) {
            throw LocalModelException("Photos require a vision model. Download and select LFM3 VL · 2B in Models.")
        }

        withContext(Dispatchers.IO) {
            val quantizeKv = request.model.family == ModelFamily.BONSAI
            val ctxSize = request.model.contextSize
            val wantGpu = preferVulkanGpu
            if (handle == 0L ||
                loadedPath != request.modelPath ||
                loadedProjectorPath != wantProjector ||
                loadedContextSize != ctxSize ||
                loadedQuantizedKv != quantizeKv ||
                loadedGpuPreference != wantGpu
            ) {
                val oldHandle = synchronized(handleLock) {
                    val cur = handle
                    handle = 0L
                    cur
                }
                if (oldHandle != 0L) {
                    LlamaNative.nativeUnloadModel(oldHandle)
                }
                val newHandle = LlamaNative.nativeLoadModel(
                    path = request.modelPath,
                    projectorPath = wantProjector,
                    contextSize = ctxSize,
                    nThreads = availableThreads,
                    quantizeKvQ4 = quantizeKv,
                    useGpu = wantGpu
                )
                if (newHandle == 0L) {
                    throw LocalModelException("The model could not load. Close other apps or choose a smaller model.")
                }
                synchronized(handleLock) {
                    handle = newHandle
                }
                loadedPath = request.modelPath
                loadedProjectorPath = wantProjector
                loadedContextSize = ctxSize
                loadedQuantizedKv = quantizeKv
                loadedGpuPreference = wantGpu
            }

            val backendLabel = if (LlamaNative.nativeIsSessionGpu(handle)) {
                "llama.cpp Vulkan GPU"
            } else {
                "llama.cpp CPU ($availableThreads threads)"
            }

            if (cancellation.isCancelled) {
                return@withContext InferenceTelemetry(0, 0, 0, 0, 0, backendLabel)
            }

            val sampling = request.model.family.sampling
            val grammarStr = request.grammar?.text
            val triggers = request.grammar?.triggerPatterns?.toTypedArray()

            val cb = object : LlamaNative.TokenCallback {
                override fun onToken(token: String) {
                    if (cancellation.isCancelled) {
                        LlamaNative.nativeCancel(handle)
                        return
                    }
                    onToken(token)
                    if (cancellation.isCancelled) {
                        LlamaNative.nativeCancel(handle)
                    }
                }
            }

            val stats = if (request.images.isNotEmpty()) {
                LlamaNative.nativeGenerateVision(
                    handle = handle,
                    prompt = request.prompt,
                    widths = request.images.map { it.width }.toIntArray(),
                    heights = request.images.map { it.height }.toIntArray(),
                    rgbArrays = request.images.map { it.rgbBytes }.toTypedArray(),
                    maxTokens = request.maxTokens,
                    addSpecial = request.model.family.addsTokenizerSpecialTokens,
                    temp = sampling.temperature,
                    topK = sampling.topK,
                    topP = sampling.topP,
                    minP = sampling.minP,
                    repeatPenalty = sampling.repeatPenalty,
                    greedy = request.greedy,
                    grammar = grammarStr,
                    triggerPatterns = triggers,
                    callback = cb
                )
            } else {
                LlamaNative.nativeGenerate(
                    handle = handle,
                    prompt = request.prompt,
                    maxTokens = request.maxTokens,
                    addSpecial = request.model.family.addsTokenizerSpecialTokens,
                    temp = sampling.temperature,
                    topK = sampling.topK,
                    topP = sampling.topP,
                    minP = sampling.minP,
                    repeatPenalty = sampling.repeatPenalty,
                    greedy = request.greedy,
                    grammar = grammarStr,
                    triggerPatterns = triggers,
                    callback = cb
                )
            }

            val status = stats.getOrElse(5) { -1L }
            when (status) {
                -3L -> throw LocalModelException("This conversation exceeds the model's context. Start a new chat or send a shorter message.")
                -4L -> throw LocalModelException("The model stopped unexpectedly while decoding. Your partial reply has been kept.")
                -5L -> throw LocalModelException("Could not encode the attached photo. Make sure LFM3 VL · 2B is fully downloaded.")
                -1L, -2L -> throw LocalModelException("Could not run the local model on this prompt.")
            }

            InferenceTelemetry(
                promptTokens = stats.getOrElse(0) { 0L }.toInt(),
                reusedTokens = stats.getOrElse(1) { 0L }.toInt(),
                generatedTokens = stats.getOrElse(2) { 0L }.toInt(),
                prefillMs = stats.getOrElse(3) { 0L },
                decodeMs = stats.getOrElse(4) { 0L },
                backend = backendLabel
            )
        }
    }

    companion object {
        fun preferredCpuThreads(): Int {
            val cores = Runtime.getRuntime().availableProcessors()
            return (cores - 2).coerceIn(2, 4)
        }
    }
}
