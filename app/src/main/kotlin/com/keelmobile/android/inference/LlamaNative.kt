package com.keelmobile.android.inference

import com.keelmobile.harness.InferenceCancellation
import com.keelmobile.harness.InferenceTelemetry
import com.keelmobile.harness.LocalGenerationRequest
import com.keelmobile.harness.LocalInferenceEngine
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
    external fun nativeSystemInfo(): String
    external fun nativeLoadModel(path: String, contextSize: Int, nThreads: Int, quantizeKvQ4: Boolean): Long
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

    fun safeSystemInfo(): String =
        if (isAvailable) runCatching { nativeSystemInfo() }.getOrDefault("llama.cpp (unavailable)")
        else "llama.cpp native library not loaded (JVM/test environment)"
}

class LocalModelException(message: String) : Exception(message)

/**
 * Single-model on-device llama.cpp runner with cancellation, Q4_0 KV cache
 * for Bonsai 2, append-only prefix caching, and explicit unload on memory/thermal pressure.
 */
class AndroidLocalInference(
    private val availableThreads: Int = preferredCpuThreads()
) : LocalInferenceEngine {

    private val mutex = Mutex()
    @Volatile
    private var handle: Long = 0L
    private var loadedPath: String? = null
    private var loadedContextSize: Int = 0
    private var loadedQuantizedKv: Boolean = false

    fun cancelActive() {
        val h = handle
        if (h != 0L && LlamaNative.isAvailable) {
            LlamaNative.nativeCancel(h)
        }
    }

    suspend fun unload() = mutex.withLock {
        withContext(Dispatchers.IO) {
            val h = handle
            handle = 0L
            loadedPath = null
            loadedContextSize = 0
            if (h != 0L && LlamaNative.isAvailable) {
                LlamaNative.nativeUnloadModel(h)
            }
        }
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

        withContext(Dispatchers.IO) {
            val quantizeKv = request.model.family == ModelFamily.BONSAI
            val ctxSize = request.model.contextSize
            if (handle == 0L || loadedPath != request.modelPath || loadedContextSize != ctxSize || loadedQuantizedKv != quantizeKv) {
                if (handle != 0L) {
                    LlamaNative.nativeUnloadModel(handle)
                    handle = 0L
                }
                val newHandle = LlamaNative.nativeLoadModel(
                    path = request.modelPath,
                    contextSize = ctxSize,
                    nThreads = availableThreads,
                    quantizeKvQ4 = quantizeKv
                )
                if (newHandle == 0L) {
                    throw LocalModelException("The model could not load. Close other apps or choose a smaller model.")
                }
                handle = newHandle
                loadedPath = request.modelPath
                loadedContextSize = ctxSize
                loadedQuantizedKv = quantizeKv
            }

            if (cancellation.isCancelled) {
                return@withContext InferenceTelemetry(0, 0, 0, 0, 0, "llama.cpp CPU")
            }

            val sampling = request.model.family.sampling
            val grammarStr = request.grammar?.text
            val triggers = request.grammar?.triggerPatterns?.toTypedArray()

            // Bridge Kotlin cancellation flag to native atomic flag while streaming
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

            val stats = LlamaNative.nativeGenerate(
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

            val status = stats.getOrElse(5) { -1L }
            when (status) {
                -3L -> throw LocalModelException("This conversation exceeds the model's context. Start a new chat or send a shorter message.")
                -4L -> throw LocalModelException("The model stopped unexpectedly while decoding. Your partial reply has been kept.")
                -1L, -2L -> throw LocalModelException("Could not run the local model on this prompt.")
            }

            InferenceTelemetry(
                promptTokens = stats.getOrElse(0) { 0L }.toInt(),
                reusedTokens = stats.getOrElse(1) { 0L }.toInt(),
                generatedTokens = stats.getOrElse(2) { 0L }.toInt(),
                prefillMs = stats.getOrElse(3) { 0L },
                decodeMs = stats.getOrElse(4) { 0L },
                backend = "llama.cpp CPU ($availableThreads threads)"
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
