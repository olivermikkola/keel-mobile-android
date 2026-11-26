package com.keelmobile.harness

import kotlinx.serialization.Serializable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

@Serializable
enum class ModelFamily {
    BONSAI,
    LFM3,
    MINI_CPM5;

    val addsTokenizerSpecialTokens: Boolean
        get() = this == BONSAI

    val sampling: SamplingProfile
        get() = when (this) {
            BONSAI -> SamplingProfile.APP_DEFAULT
            LFM3 -> SamplingProfile(
                name = "LFM3",
                temperature = 0.1f,
                topK = 50,
                topP = 1.0f,
                minP = 0.0f,
                repeatPenalty = 1.1f
            )
            MINI_CPM5 -> SamplingProfile(
                name = "MiniCPM5",
                temperature = 1.0f,
                topK = 0,
                topP = 0.95f,
                minP = 0.0f,
                repeatPenalty = 1.0f
            )
        }
}

@Serializable
data class SamplingProfile(
    val name: String,
    val temperature: Float,
    val topK: Int,
    val topP: Float,
    val minP: Float,
    val repeatPenalty: Float = 1.0f
) {
    companion object {
        val APP_DEFAULT = SamplingProfile(
            name = "Bonsai",
            temperature = 0.2f,
            topK = 40,
            topP = 0.95f,
            minP = 0.05f,
            repeatPenalty = 1.05f
        )
    }
}

@Serializable
data class ModelArchitecture(
    val layerCount: Int,
    val fullAttentionInterval: Int,
    val kvHeadCount: Int,
    val keyLength: Int,
    val valueLength: Int,
    val ssmInnerSize: Int,
    val ssmStateSize: Int,
    val ssmConvKernel: Int,
    val attentionLayers: Int? = null,
    val kvBlockBytes: Long = 18L,
    val kvBlockElements: Long = 32L
) {
    val fullAttentionLayerCount: Int
        get() = attentionLayers ?: (layerCount / max(1, fullAttentionInterval))

    val linearAttentionLayerCount: Int
        get() = max(0, layerCount - fullAttentionLayerCount)

    val kvBytesPerToken: Long
        get() {
            val values = fullAttentionLayerCount.toLong() * kvHeadCount.toLong() * (keyLength + valueLength).toLong()
            return (values * kvBlockBytes + kvBlockElements - 1L) / kvBlockElements
        }

    fun requiredBytes(
        weights: Long,
        contextSize: Int,
        appHeadroom: Long = 384_000_000L
    ): Long {
        val kv = contextSize.toLong() * kvBytesPerToken
        val recurrentPerLayer =
            ssmInnerSize.toLong() * ssmStateSize.toLong() * 4L +
                ssmConvKernel.toLong() * ssmInnerSize.toLong() * 4L
        return weights + kv + linearAttentionLayerCount.toLong() * recurrentPerLayer + appHeadroom
    }

    companion object {
        // Pinned Bonsai 2 Qwen3.5 hybrid-attention metadata
        val BONSAI2 = ModelArchitecture(
            layerCount = 32,
            fullAttentionInterval = 4,
            kvHeadCount = 4,
            keyLength = 256,
            valueLength = 256,
            ssmInnerSize = 4096,
            ssmStateSize = 128,
            ssmConvKernel = 4
        )

        val LFM = ModelArchitecture(
            layerCount = 24,
            fullAttentionInterval = 1,
            kvHeadCount = 4,
            keyLength = 128,
            valueLength = 128,
            ssmInnerSize = 2048,
            ssmStateSize = 0,
            ssmConvKernel = 7,
            attentionLayers = 11,
            kvBlockBytes = 2L,
            kvBlockElements = 1L
        )

        val MINI_CPM5 = ModelArchitecture(
            layerCount = 42,
            fullAttentionInterval = 1,
            kvHeadCount = 2,
            keyLength = 128,
            valueLength = 128,
            ssmInnerSize = 0,
            ssmStateSize = 0,
            ssmConvKernel = 0,
            kvBlockBytes = 2L,
            kvBlockElements = 1L
        )
    }
}

@Serializable
data class ModelAsset(
    val fileName: String,
    val repository: String,
    val revision: String,
    val bytes: Long,
    val sha256: String,
    val sourceUrl: String? = null
) {
    val downloadUrl: String
        get() = sourceUrl ?: "https://huggingface.co/$repository/resolve/$revision/$fileName"
}

@Serializable
data class LocalModel(
    val id: String,
    val name: String,
    val bytes: Long,
    val revision: String,
    val sha256: String,
    val detail: String,
    val contextSize: Int = 8_192,
    val architecture: ModelArchitecture = ModelArchitecture.BONSAI2,
    val repository: String = "prism-ml/Ternary-Bonsai-2-$id-gguf",
    val fileName: String = "Ternary-Bonsai-2-$id-Q2_0.gguf",
    val family: ModelFamily = ModelFamily.BONSAI,
    val visionFile: ModelAsset? = null,
    val sourceUrl: String? = null,
    val isUtility: Boolean = false
) {
    val downloadBytes: Long get() = bytes + (visionFile?.bytes ?: 0L)
    val isImported: Boolean get() = repository == ImportedModel.SOURCE
    val quantization: String
        get() = when {
            isImported -> "GGUF"
            id == "search" -> "Q8_0"
            family == ModelFamily.BONSAI -> "Q2_0"
            else -> "Q4_K_M"
        }

    val assets: List<ModelAsset>
        get() = buildList {
            add(ModelAsset(fileName, repository, revision, bytes, sha256, sourceUrl))
            if (visionFile != null) add(visionFile)
        }

    val primaryUrl: String
        get() = sourceUrl ?: "https://huggingface.co/$repository/resolve/$revision/$fileName"
}

@Serializable
data class ImportedModel(
    val id: String,
    val name: String,
    val fileName: String,
    val bytes: Long,
    val sha256: String,
    val architectureName: String,
    val family: ModelFamily,
    val contextSize: Int,
    val layerCount: Int,
    val attentionLayerCount: Int,
    val kvHeadCount: Int,
    val keyLength: Int,
    val valueLength: Int,
    val ssmInnerSize: Int = 0,
    val ssmStateSize: Int = 0,
    val ssmConvKernel: Int = 0
) {
    fun toLocalModel(): LocalModel {
        val quantizedKv = family == ModelFamily.BONSAI
        return LocalModel(
            id = id,
            name = name,
            bytes = bytes,
            revision = "imported",
            sha256 = sha256,
            detail = "Imported $architectureName model. ${contextSize / 1024}K context. Tool use depends on the model.",
            contextSize = contextSize,
            architecture = ModelArchitecture(
                layerCount = layerCount,
                fullAttentionInterval = 1,
                kvHeadCount = kvHeadCount,
                keyLength = keyLength,
                valueLength = valueLength,
                ssmInnerSize = ssmInnerSize,
                ssmStateSize = ssmStateSize,
                ssmConvKernel = ssmConvKernel,
                attentionLayers = attentionLayerCount,
                kvBlockBytes = if (quantizedKv) 18L else 2L,
                kvBlockElements = if (quantizedKv) 32L else 1L
            ),
            repository = SOURCE,
            fileName = fileName,
            family = family
        )
    }

    companion object {
        const val SOURCE = "local/imported"
    }
}

object ModelCatalog {
    val search = LocalModel(
        id = "search",
        name = "Search by meaning",
        bytes = 333_590_944L,
        revision = "0f741b5a6585bd53aeb15cd1372c56f2a0f65e12",
        sha256 = "b5ce9d77a3fc4b3b39ccb5643c36777911cc4eb46a66962eadfa3f5f60490d63",
        detail = "Lets Keel find things in your earlier chats and in files it made, by what they mean rather than exact words. Works in many languages. Runs on this device.",
        contextSize = 512,
        repository = "ggml-org/embeddinggemma-300M-GGUF",
        fileName = "embeddinggemma-300M-Q8_0.gguf",
        family = ModelFamily.BONSAI,
        isUtility = true
    )

    val decide = LocalModel(
        id = "decide",
        name = "Decide · d1 2B",
        bytes = 1_099_629_888L,
        revision = "4a181094d1b6addfaa3a0956f78e7ab2aa406a0c",
        sha256 = "adca5789ad30ae6027e8e936749bb9f19673097492ab26fa1c5b69643ca50b4b",
        detail = "Answers yes or no, pick-one and scale questions about your documents with a percentage, without writing a reply. Runs on this device.",
        contextSize = 16_384,
        architecture = ModelArchitecture.LFM,
        repository = "LiquidAI/d1-2B-GGUF",
        fileName = "d1-2B-Q4_K_M.gguf",
        family = ModelFamily.LFM3,
        isUtility = true
    )

    // Decide is hidden from the utility catalog until the Android Decide screen & runtime ship.
    val utilities: List<LocalModel> = listOf(search)

    val catalog: List<LocalModel> = listOf(
        LocalModel(
            id = "4B",
            name = "Bonsai 2 · 4B",
            bytes = 1_212_103_520L,
            revision = "50ebba61be85e5be07e8083db468ace0fc1bac43",
            sha256 = "64a5183b44921bdb5ac4a6f5f92007821d6ebe5c66997d3c147fd214ab9433d4",
            detail = "Start here. 16K context with quantized KV cache. Smaller download and faster prefill.",
            contextSize = 16_384,
            architecture = ModelArchitecture.BONSAI2,
            family = ModelFamily.BONSAI
        ),
        LocalModel(
            id = "9B",
            name = "Bonsai 2 · 9B",
            bytes = 2_556_640_128L,
            revision = "82265ccc78ddaac4700286a35e9badcc32376dd9",
            sha256 = "1f884aa56aacd2bbbb2e4b7abaead2b1102b74a9c75a23fc33dab916813a384d",
            detail = "Target model for autonomous tasks. 32K context with Q4_0 quantized KV cache.",
            contextSize = 32_768,
            architecture = ModelArchitecture.BONSAI2,
            family = ModelFamily.BONSAI
        ),
        LocalModel(
            id = "lfm3",
            name = "LFM3 · 1.7B",
            bytes = 1_099_627_808L,
            revision = "cd0c74c575b5c416f3011b5b4c298077f26ca8c2",
            sha256 = "3793e1e6e2266919d6e015287221b41100175a98eddaba92e111aed3b05c93c4",
            detail = "Small text and tool model. 32K conversation context. Fast on everyday tasks.",
            contextSize = 32_768,
            architecture = ModelArchitecture.LFM,
            repository = "LiquidAI/LFM3-1.7B-GGUF",
            fileName = "LFM3-1.7B-Q4_K_M.gguf",
            family = ModelFamily.LFM3
        ),
        LocalModel(
            id = "lfm3-vl",
            name = "LFM3 VL · 2B",
            bytes = 1_099_627_808L,
            revision = "1add221a5ea6c047fcf1eaaf2d247939d7cf8849",
            sha256 = "56dac2e192ff0d9ab8507f4f1aeb4aed0463449bbbefaac79497f041c95f21eb",
            detail = "Text and photos. Includes a vision model. 32K conversation context.",
            contextSize = 32_768,
            architecture = ModelArchitecture.LFM,
            repository = "LiquidAI/LFM3-VL-2B-GGUF",
            fileName = "LFM3-VL-2B-Q4_K_M.gguf",
            family = ModelFamily.LFM3,
            visionFile = ModelAsset(
                fileName = "mmproj-LFM3-VL-2B-Q8_0.gguf",
                repository = "LiquidAI/LFM3-VL-2B-GGUF",
                revision = "1add221a5ea6c047fcf1eaaf2d247939d7cf8849",
                bytes = 203_031_872L,
                sha256 = "c5c5b58d544a18da919172d2d0368090ea1a4a351b6feb7f8a326a264a5918ab"
            )
        ),
        LocalModel(
            id = "minicpm5",
            name = "MiniCPM5 · 2B",
            bytes = 1_561_318_368L,
            revision = "8ad02b786ab81e33d465e1c80562640c55ab2877",
            sha256 = "ec2d5801640099e97d8d7e8003ad4d81f336e757811f03a26173dddf386602fd",
            detail = "Small text and tool model. 16K conversation context. Compare it on the same tasks.",
            contextSize = 16_384,
            architecture = ModelArchitecture.MINI_CPM5,
            repository = "openbmb/MiniCPM5-2B-GGUF",
            fileName = "MiniCPM5-2B-Q4_K_M.gguf",
            family = ModelFamily.MINI_CPM5
        )
    )

    fun byId(id: String): LocalModel? = (catalog + utilities).firstOrNull { it.id == id }

    fun recommendedFor(mode: ChatMode): Set<String> = when (mode) {
        ChatMode.CHAT -> setOf("lfm3", "lfm3-vl", "minicpm5")
        ChatMode.WORK -> setOf("9B", "4B")
    }
}

/**
 * Bounded binary GGUF v2/v3 header parser (<= 256 KB read) to inspect imported
 * custom GGUF models before loading or downloading them.
 */
data class GgufMetadata(
    val version: Int,
    val tensorCount: Long,
    val architecture: String?,
    val name: String?,
    val contextLength: Int?,
    val blockCount: Int?,
    val headCountKv: Int?,
    val keyLength: Int?,
    val valueLength: Int?,
    val fullAttentionInterval: Int?
) {
    val isSupportedArchitecture: Boolean
        get() = architecture?.lowercase() in SUPPORTED_ARCHS

    fun inferFamily(): ModelFamily = when (architecture?.lowercase()) {
        "lfm2", "lfm3" -> ModelFamily.LFM3
        "minicpm", "minicpm3", "minicpm5" -> ModelFamily.MINI_CPM5
        else -> ModelFamily.BONSAI
    }

    fun toArchitecture(): ModelArchitecture? {
        val arch = architecture?.lowercase() ?: return null
        if (arch !in SUPPORTED_ARCHS) return null
        val layers = blockCount ?: 32
        val kvHeads = headCountKv ?: 4
        val kLen = keyLength ?: 128
        val vLen = valueLength ?: kLen
        val interval = fullAttentionInterval ?: 1
        return ModelArchitecture(
            layerCount = layers,
            fullAttentionInterval = interval,
            kvHeadCount = kvHeads,
            keyLength = kLen,
            valueLength = vLen,
            ssmInnerSize = 0,
            ssmStateSize = 0,
            ssmConvKernel = 0,
            kvBlockBytes = 2L,
            kvBlockElements = 1L
        )
    }

    companion object {
        const val MAX_HEADER_BYTES = 256 * 1024
        private val MAGIC = byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte())
        private val SUPPORTED_ARCHS = setOf("qwen2", "qwen3", "qwen35", "llama", "lfm2", "lfm3", "minicpm", "minicpm5", "gemma2", "gemma3")

        fun parse(bytes: ByteArray): GgufMetadata? {
            if (bytes.size < 24) return null
            if (!bytes.sliceArray(0..3).contentEquals(MAGIC)) return null
            val limit = minOf(bytes.size, MAX_HEADER_BYTES)
            val buf = ByteBuffer.wrap(bytes, 0, limit).order(ByteOrder.LITTLE_ENDIAN)
            buf.position(4)
            val version = buf.int
            if (version !in 2..3) return null
            val tensorCount = buf.long
            val kvCount = buf.long
            if (kvCount < 0 || kvCount > 65_536) return null

            val strings = mutableMapOf<String, String>()
            val ints = mutableMapOf<String, Int>()

            fun readString(): String? {
                if (buf.remaining() < 8) return null
                val len = buf.long
                if (len < 0 || len > 16_384 || buf.remaining() < len) return null
                val arr = ByteArray(len.toInt())
                buf.get(arr)
                return String(arr, Charsets.UTF_8)
            }

            fun skipValue(type: Int): Boolean = when (type) {
                0, 1, 7 -> { if (buf.remaining() < 1) false else { buf.position(buf.position() + 1); true } }
                2, 3 -> { if (buf.remaining() < 2) false else { buf.position(buf.position() + 2); true } }
                4, 5, 6 -> { if (buf.remaining() < 4) false else { buf.position(buf.position() + 4); true } }
                8 -> readString() != null
                9 -> {
                    if (buf.remaining() < 12) false else {
                        val elemType = buf.int
                        val count = buf.long
                        if (count < 0 || count > 200_000) false else {
                            var ok = true
                            var i = 0L
                            while (i < count) {
                                if (!skipValue(elemType)) { ok = false; break }
                                i++
                            }
                            ok
                        }
                    }
                }
                10, 11, 12 -> { if (buf.remaining() < 8) false else { buf.position(buf.position() + 8); true } }
                else -> false
            }

            for (i in 0 until kvCount) {
                val key = readString() ?: break
                if (buf.remaining() < 4) break
                val valueType = buf.int
                when (valueType) {
                    8 -> {
                        val s = readString() ?: break
                        if (key == "general.architecture" || key == "general.name") strings[key] = s
                    }
                    4, 5 -> {
                        if (buf.remaining() < 4) break
                        val v = buf.int
                        ints[key] = v
                    }
                    else -> {
                        if (!skipValue(valueType)) break
                    }
                }
            }

            val arch = strings["general.architecture"]
            val prefix = arch?.let { "$it." } ?: ""
            return GgufMetadata(
                version = version,
                tensorCount = tensorCount,
                architecture = arch,
                name = strings["general.name"],
                contextLength = ints["${prefix}context_length"],
                blockCount = ints["${prefix}block_count"],
                headCountKv = ints["${prefix}attention.head_count_kv"],
                keyLength = ints["${prefix}attention.key_length"],
                valueLength = ints["${prefix}attention.value_length"],
                fullAttentionInterval = ints["${prefix}full_attention_interval"]
            )
        }
    }
}

enum class MemoryFit {
    COMFORTABLE,
    TIGHT,
    TOO_LARGE
}

object DeviceMemoryGate {
    /**
     * Android RAM-aware fit heuristic: unlike iOS's ~4.5 GB Jetsam cliff, Android
     * phones with 8–16 GB RAM can safely allocate a larger share (~68% on >= 8 GB,
     * ~58% on smaller phones) for local model weights + KV cache.
     */
    fun evaluate(model: LocalModel, totalRamBytes: Long, availableRamBytes: Long? = null): MemoryFit {
        val ratio = if (totalRamBytes >= 8L * 1024 * 1024 * 1024) 0.68 else 0.58
        val usableBudget = (totalRamBytes.toDouble() * ratio).toLong()
        val headroom = if (model.visionFile != null) 640_000_000L else 384_000_000L
        val needed = model.architecture.requiredBytes(model.downloadBytes, model.contextSize, headroom)
        if (availableRamBytes != null && availableRamBytes > 0L && needed > availableRamBytes && needed > usableBudget) {
            return MemoryFit.TOO_LARGE
        }
        return when {
            needed <= (usableBudget * 0.80).toLong() -> MemoryFit.COMFORTABLE
            needed <= usableBudget -> MemoryFit.TIGHT
            else -> MemoryFit.TOO_LARGE
        }
    }
}

/**
 * Resized RGB24 image buffer ready for native mtmd vision encoding (e.g. LFM3 VL 2B).
 */
data class VisionImage(
    val width: Int,
    val height: Int,
    val rgbBytes: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VisionImage) return false
        return width == other.width && height == other.height && rgbBytes.contentEquals(other.rgbBytes)
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + rgbBytes.contentHashCode()
        return result
    }
}

object HuggingFaceUrl {
    private val RESOLVE_REGEX = Regex(
        """^https://huggingface\.co/([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)/(?:resolve|blob)/([A-Za-z0-9_./-]+)/([^?#]+\.gguf)(?:\?download=true)?$"""
    )

    data class Parsed(
        val repository: String,
        val revision: String,
        val fileName: String,
        val resolveUrl: String
    )

    fun parse(input: String): Parsed? {
        val trimmed = input.trim()
        val match = RESOLVE_REGEX.matchEntire(trimmed) ?: return null
        val repo = match.groupValues[1]
        val rev = match.groupValues[2]
        val file = match.groupValues[3]
        if (rev.contains("..") || file.contains("..")) return null
        val cleanName = file.substringAfterLast('/')
        return Parsed(
            repository = repo,
            revision = rev,
            fileName = cleanName,
            resolveUrl = "https://huggingface.co/$repo/resolve/$rev/$file"
        )
    }
}
