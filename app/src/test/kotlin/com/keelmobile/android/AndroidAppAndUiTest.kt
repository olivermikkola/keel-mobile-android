package com.keelmobile.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.keelmobile.android.data.AndroidKeystoreCredentialStore
import com.keelmobile.android.data.ModelLibraryManager
import com.keelmobile.android.data.TaskStore
import com.keelmobile.android.ui.KeelAppRoot
import com.keelmobile.android.ui.KeelController
import com.keelmobile.android.ui.KeelRoute
import com.keelmobile.android.ui.theme.KeelTheme
import com.keelmobile.harness.ChatMode
import com.keelmobile.harness.InferenceCancellation
import com.keelmobile.harness.InferenceTelemetry
import com.keelmobile.harness.LocalGenerationRequest
import com.keelmobile.harness.LocalInferenceEngine
import com.keelmobile.harness.ModelCatalog
import com.keelmobile.harness.ThermalLevel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class AndroidAppAndUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("keel-ui-test").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun chatSqlProposalAndExplicitApplyFlowInComposeUi() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val modelsDir = File(tempDir, "models").apply { mkdirs() }
        // Seed a fake installed 4B model file so ModelLibrary marks 4B installed
        val model4b = ModelCatalog.byId("4B")!!
        File(modelsDir, model4b.fileName).writeText("fake-gguf-for-ui-test")

        val modelLib = ModelLibraryManager(modelsDir)
        val storeDir = File(tempDir, "store")
        val taskStore = TaskStore(storeDir)
        val scheduledStore = com.keelmobile.android.data.ScheduledTaskStore(storeDir)
        val creds = AndroidKeystoreCredentialStore(ctx)

        val scripted = ArrayDeque(
            listOf(
                "<tool_call><function=run_sql><parameter=sql>SELECT SUM(cost) AS total FROM sales.csv</parameter></function></tool_call>",
                "<tool_call><function=create_file><parameter=path>summary.md</parameter><parameter=new_text># Sales\nTotal is 45.</parameter></function></tool_call>",
                "Total sales are 45. Review and apply summary.md below."
            )
        )
        val fakeEngine = object : LocalInferenceEngine {
            override suspend fun generate(
                request: LocalGenerationRequest,
                cancellation: InferenceCancellation,
                onToken: (String) -> Unit
            ): InferenceTelemetry {
                val out = scripted.removeFirst()
                onToken(out)
                return InferenceTelemetry(80, 0, 18, 5, 10, "robolectric-fake")
            }
        }

        val controller = KeelController(
            taskStore = taskStore,
            modelLibrary = modelLib,
            credentials = creds,
            engine = fakeEngine,
            totalRamBytes = 8L * 1024 * 1024 * 1024,
            scheduledStore = scheduledStore
        )
        controller.attachFile("sales.csv", "item,cost\nA,20\nB,25\n")

        composeRule.setContent {
            KeelTheme(darkTheme = true) {
                KeelAppRoot(
                    controller = controller,
                    onAttachFileClick = {},
                    onImportModelClick = {},
                    onDictateClick = {},
                    onSpeakText = {},
                    onSharePreview = {}
                )
            }
        }

        // Send prompt via Compose UI
        composeRule.onNodeWithTag("composer_input").performTextInput("Sum sales.csv and write summary.md")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("send_button")
            .assertIsDisplayed()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        composeRule.waitForIdle()

        composeRule.waitUntil(5_000) {
            controller.state.value.activeTask.messages.any { it.role == "user" } &&
                !controller.state.value.isGenerating
        }
        val afterState = controller.state.value
        assertTrue(
            afterState.activeTask.pendingProposals.isNotEmpty(),
            "Expected pending proposal, got banner=${afterState.bannerMessage}, steps=${afterState.liveSteps}, msgs=${afterState.activeTask.messages}"
        )

        // Reply & proposal card are visible; file is NOT applied yet
        composeRule.onNodeWithText("Total sales are 45. Review and apply summary.md below.").assertIsDisplayed()
        composeRule.onNodeWithTag("proposal_card_summary.md").assertIsDisplayed()
        assertTrue(controller.state.value.libraryItems.none { it.path == "summary.md" })

        // Explicit Apply tap applies the file to workspace & Library
        composeRule.onNodeWithTag("apply_proposal_button").performClick()
        composeRule.waitForIdle()
        assertTrue(controller.state.value.libraryItems.any { it.path == "summary.md" })

        // Toggle Work -> Chat mode & navigate to Models screen
        composeRule.onNodeWithTag("mode_toggle").performClick()
        assertEquals(ChatMode.CHAT, controller.state.value.activeTask.mode)

        composeRule.onNodeWithTag("model_chip").performClick()
        composeRule.waitForIdle()
        assertEquals(KeelRoute.MODELS, controller.state.value.route)
        composeRule.onNodeWithTag("model_card_4B").assertIsDisplayed()

        // Thermal critical status fails closed with clear user message
        controller.onThermalStatusChanged(ThermalLevel.CRITICAL)
        assertTrue(controller.state.value.bannerMessage?.contains("high temperature", ignoreCase = true) == true)

        // Vulkan GPU preference toggle updates state & persists
        controller.setUseVulkanGpu(false)
        assertEquals(false, controller.state.value.preferences.useVulkanGpu)
        controller.setUseVulkanGpu(true)
        assertEquals(true, controller.state.value.preferences.useVulkanGpu)

        // Android Share Sheet intake creates a new chat with shared text attached
        controller.receiveSharedText("https://example.org/spec")
        val exported = controller.exportActiveChatMarkdown()
        assertTrue(exported.rawText.contains("example.org/spec", ignoreCase = true) || controller.state.value.bannerMessage?.contains("Shared", ignoreCase = true) == true)

        // Unattended scheduled tasks screen adds, toggles, and deletes schedules
        controller.navigate(KeelRoute.SCHEDULED)
        composeRule.waitForIdle()
        controller.addScheduledTask("Morning digest", "Review open todos", com.keelmobile.harness.ScheduleInterval.DAILY)
        composeRule.waitForIdle()
        assertEquals(1, controller.state.value.scheduledTasks.size)
        val schedId = controller.state.value.scheduledTasks.first().id
        composeRule.onNodeWithText("Morning digest").assertIsDisplayed()
        controller.toggleScheduledTask(schedId, false)
        assertEquals(false, controller.state.value.scheduledTasks.first().enabled)
        controller.deleteScheduledTask(schedId)
        assertTrue(controller.state.value.scheduledTasks.isEmpty())
    }
}
