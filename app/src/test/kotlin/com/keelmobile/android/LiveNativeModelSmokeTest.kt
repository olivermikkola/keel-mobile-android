package com.keelmobile.android

import com.keelmobile.android.data.AndroidSqliteDb
import com.keelmobile.android.inference.AndroidLocalInference
import com.keelmobile.android.inference.LlamaNative
import com.keelmobile.harness.AgentLoop
import com.keelmobile.harness.AgentPrompt
import com.keelmobile.harness.AppPreset
import com.keelmobile.harness.ChatMode
import com.keelmobile.harness.HarnessMessage
import com.keelmobile.harness.InferenceCancellation
import com.keelmobile.harness.ModelCatalog
import com.keelmobile.harness.SqlSandbox
import com.keelmobile.harness.TaskWorkspace
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertTrue

/**
 * Opt-in real-model integration smoke test through the exact C++ JNI bridge (`libkeel_llama.so`),
 * lazy GBNF grammar sampler, SqlSandbox, and AgentLoop.
 *
 * Activated only when KEEL_LIVE_GGUF points to a local GGUF file and libkeel_llama.so is on java.library.path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveNativeModelSmokeTest {

    @Test
    fun realLocalGgufModelRunsSqlToolThroughNativeJni() = runBlocking {
        val modelPath = System.getenv("KEEL_LIVE_GGUF")
        Assume.assumeTrue("Set KEEL_LIVE_GGUF to run live native model smoke test", !modelPath.isNullOrBlank())
        Assume.assumeTrue("GGUF file not found: $modelPath", File(modelPath!!).exists())
        Assume.assumeTrue("libkeel_llama.so not loaded via java.library.path", LlamaNative.isAvailable)

        val modelId = System.getenv("KEEL_LIVE_MODEL_ID") ?: "lfm3"
        val baseModel = ModelCatalog.byId(modelId) ?: ModelCatalog.byId("lfm3")!!
        val model = baseModel.copy(contextSize = 4096)

        val inference = AndroidLocalInference(availableThreads = 8)
        try {
            TaskWorkspace(AndroidSqliteDb.openInMemory()).use { ws ->
                ws.putTextFile("sales.csv", "item,qty,price\nEspresso,4,3.50\nCroissant,5,4.20\nMatcha,2,5.00\n")
                SqlSandbox(AndroidSqliteDb.openInMemory()).use { sql ->
                    val loop = AgentLoop(inference, ws, sql)
                    val history = mutableListOf(
                        HarnessMessage(
                            HarnessMessage.Role.USER,
                            "What is the exact total revenue (qty * price) in sales.csv?"
                        )
                    )
                    val result = loop.runTurn(
                        model = model,
                        modelPath = modelPath,
                        systemPrompt = AgentPrompt.local(app = AppPreset.SHEETS, mode = ChatMode.WORK),
                        history = history,
                        mode = ChatMode.WORK,
                        thinking = false,
                        allowedUrls = mutableSetOf(),
                        cancellation = InferenceCancellation()
                    )

                    println("LIVE_JNI_STEPS=${result.steps.map { "${it.toolName}: ${it.resultSummary}" }}")
                    println("LIVE_JNI_HISTORY=${history.joinToString("\n---MSG---\n") { "${it.role}: ${it.text}" }}")
                    println("LIVE_JNI_REPLY=${result.finalReply}")
                    println("LIVE_JNI_TELEMETRY=${result.lastTelemetry}")

                    assertTrue(
                        result.steps.any { it.toolName == "run_sql" && !it.isError },
                        "Expected real model to execute run_sql; steps=${result.steps}, reply=${result.finalReply}"
                    )
                    // 4*3.50 + 5*4.20 + 2*5.00 = 14 + 21 + 10 = 45
                    assertTrue(
                        result.finalReply.contains("45"),
                        "Expected final reply to contain exact SQL total 45; got: ${result.finalReply}"
                    )
                }
            }
        } finally {
            inference.unload()
        }
    }
}
