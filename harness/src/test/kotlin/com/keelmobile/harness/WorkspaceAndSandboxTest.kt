package com.keelmobile.harness

import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspaceAndSandboxTest {

    private fun buildGzipTar(entries: Map<String, ByteArray>): ByteArray {
        val raw = ByteArrayOutputStream()
        for ((name, bytes) in entries) {
            val header = ByteArray(512)
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            System.arraycopy(nameBytes, 0, header, 0, minOf(nameBytes.size, 100))
            val mode = "0000644\u0000".toByteArray(Charsets.US_ASCII)
            System.arraycopy(mode, 0, header, 100, mode.size)
            val sizeOctal = "%011o\u0000".format(bytes.size).toByteArray(Charsets.US_ASCII)
            System.arraycopy(sizeOctal, 0, header, 124, sizeOctal.size)
            header[156] = '0'.code.toByte()
            raw.write(header)
            raw.write(bytes)
            val pad = (512 - (bytes.size % 512)) % 512
            if (pad > 0) raw.write(ByteArray(pad))
        }
        raw.write(ByteArray(1024))
        val gz = ByteArrayOutputStream()
        GZIPOutputStream(gz).use { it.write(raw.toByteArray()) }
        return gz.toByteArray()
    }

    @Test
    fun workspaceArchiveImportSearchAndStaleBaseRejection() {
        TaskWorkspace(JdbcSqliteDb.openInMemory()).use { ws ->
            val readmeBytes = "# Hello Keel\nfun greetUser() = \"hi\"\n".toByteArray()
            val expectedSha = GitBlob.sha1Hex(readmeBytes)
            val tar = buildGzipTar(
                mapOf(
                    "repo-main/README.md" to readmeBytes,
                    "repo-main/src/App.kt" to "class UserProfileService {\n    fun loadProfile() = 42\n}\n".toByteArray(),
                    "repo-main/Makefile" to "all:\n\techo upper\n".toByteArray(),
                    "repo-main/makefile" to "all:\n\techo lower\n".toByteArray()
                )
            )
            val count = ws.importGzipTar(tar, expectedTreeShas = mapOf("README.md" to expectedSha, "src/App.kt" to GitBlob.sha1Hex("class UserProfileService {\n    fun loadProfile() = 42\n}\n".toByteArray()), "Makefile" to GitBlob.sha1Hex("all:\n\techo upper\n".toByteArray()), "makefile" to GitBlob.sha1Hex("all:\n\techo lower\n".toByteArray())))
            assertEquals(4, count)

            // Case-sensitive keys remain distinct
            assertEquals("all:\n\techo upper\n", ws.readText("Makefile"))
            assertEquals("all:\n\techo lower\n", ws.readText("makefile"))

            // Path traversal rejected
            assertNull(TaskWorkspace.normalizePath("../etc/passwd"))

            // Multi-mode search: exact, case-insensitive, camelCase words
            assertEquals("exact", ws.search("greetUser").mode)
            assertEquals("case-insensitive", ws.search("GREETUSER").mode)
            val camel = ws.search("profile service")
            assertEquals("words", camel.mode)
            assertTrue(camel.matches.any { it.path == "src/App.kt" })

            // Repo map is byte-stable
            assertEquals(ws.repoMap(), ws.repoMap())

            // Edit proposal does NOT mutate until applied, and rejects stale base
            val proposal = ws.planEdit("p1", EditOperation.REPLACE, "README.md", "Hello Keel", "Hello Android")
            assertTrue(ws.readText("README.md")!!.contains("Hello Keel"))
            ws.putTextFile("README.md", "Changed concurrently")
            assertFailsWith<IllegalStateException> { ws.applyProposal(proposal) }
        }
    }

    @Test
    fun sqlSandboxMountsCsvFilesAndBlocksUnsafeSql() {
        TaskWorkspace(JdbcSqliteDb.openInMemory()).use { ws ->
            ws.putTextFile("sales.csv", "item,qty,price\nCoffee,2,4.50\nTea,3,3.00\n")
            ws.putTextFile("notes.md", "# Notes\nHello")

            SqlSandbox(JdbcSqliteDb.openInMemory()).use { sandbox ->
                sandbox.refreshWorkspaceViews(ws)

                val sum = sandbox.execute("SELECT SUM(qty * price) AS revenue FROM sales.csv")
                assertTrue(sum.output.contains("18"))

                val files = sandbox.execute("SELECT path, kind FROM files ORDER BY path")
                assertTrue(files.output.contains("sales.csv | csv"))
                assertTrue(files.output.contains("notes.md | markdown"))

                assertFailsWith<SecurityException> {
                    sandbox.execute("ATTACH DATABASE '/tmp/pwn.db' AS pwn")
                }
                assertFailsWith<SecurityException> {
                    sandbox.execute("PRAGMA writable_schema = ON")
                }
            }
        }
    }

    @Test
    fun scriptSandboxExecutesJsAndRoutesWritesToProposals() {
        TaskWorkspace(JdbcSqliteDb.openInMemory()).use { ws ->
            ws.putTextFile("data.json", """{"nums":[10,20,30]}""")

            val sandbox = ScriptSandbox(ws, allowWrites = true, timeoutMs = 2_000L)
            val result = sandbox.run(
                """
                const raw = await keel.readFile("data.json");
                const obj = JSON.parse(raw);
                const total = obj.nums.reduce((a, b) => a + b, 0);
                console.log("total=" + total);
                await keel.writeFile("summary.txt", "Total is " + total);
                return total;
                """.trimIndent()
            )
            assertTrue(result.output.contains("total=60"))
            assertEquals("60", result.returnValue)
            assertEquals(1, result.proposals.size)
            assertEquals("summary.txt", result.proposals.first().path)
            // Workspace NOT modified until user approves
            assertNull(ws.readText("summary.txt"))

            ws.applyProposal(result.proposals.first())
            assertEquals("Total is 60", ws.readText("summary.txt"))

            // Infinite loop aborted cleanly
            val shortTimeout = ScriptSandbox(ws, timeoutMs = 150L)
            assertFailsWith<IllegalStateException> {
                shortTimeout.run("while (true) {}")
            }

            // Java reflection blocked by ClassShutter
            assertFailsWith<Exception> {
                sandbox.run("java.lang.Runtime.getRuntime().exec('id');")
            }
        }
    }

    @Test
    fun viewersDefuseLinksParseStudyDeckAndRenderOfflineHtml() {
        val segments = ChatBlockParser.parseSegments("See [Docs](https://example.com)\n```kotlin\nval x = 1\r\n```\nDone")
        assertEquals(3, segments.size)
        val prose = segments[0] as MessageSegment.Prose
        assertEquals("See Docs [https://example.com]", (prose.blocks.first() as ProseBlock.Paragraph).text)
        val code = segments[1] as MessageSegment.CodeBlock
        assertEquals("kotlin", code.language)
        assertEquals("val x = 1", code.code)

        val deck = StudyDeck.parse(
            """
            # Planets
            Q: Closest planet to the Sun?
            - Venus
            - Mercury
            A: Mercury

            Q: Largest planet?
            A: Jupiter
            """.trimIndent()
        )
        assertEquals("Planets", deck.title)
        assertEquals(2, deck.cards.size)
        assertTrue(deck.cards[0].isQuiz)
        assertEquals(listOf(1), deck.cards[0].correct)
        assertFalse(deck.cards[1].isQuiz)

        TaskWorkspace(JdbcSqliteDb.openInMemory()).use { ws ->
            ws.putTextFile("index.html", "<html><head><link rel=\"stylesheet\" href=\"app.css\"></head><body>Hi</body></html>")
            ws.putTextFile("app.css", "body { color: #36b394; }")
            val html = FileView.renderHtmlPreview("index.html", ws)
            assertTrue(html.contains(FileView.OFFLINE_CSP))
            assertTrue(html.contains("color: #36b394"))
        }
    }

    @Test
    fun agentLoopRunsSqlThenProposesFileWithExplicitApproval() = runTest {
        TaskWorkspace(JdbcSqliteDb.openInMemory()).use { ws ->
            ws.putTextFile("expenses.csv", "cat,cost\nFood,12\nBus,3\nFood,8\n")
            SqlSandbox(JdbcSqliteDb.openInMemory()).use { sql ->
                val scriptedOutputs = ArrayDeque(
                    listOf(
                        "<tool_call><function=run_sql><parameter=sql>SELECT SUM(cost) AS total FROM expenses.csv</parameter></function></tool_call>",
                        "<tool_call><function=create_file><parameter=path>report.md</parameter><parameter=new_text># Total\nTotal cost is 23.</parameter></function></tool_call>",
                        "The total cost is 23, and I proposed report.md for your review."
                    )
                )
                val fakeEngine = object : LocalInferenceEngine {
                    override suspend fun generate(
                        request: LocalGenerationRequest,
                        cancellation: InferenceCancellation,
                        onToken: (String) -> Unit
                    ): InferenceTelemetry {
                        assertNotNull(request.grammar)
                        val reply = scriptedOutputs.removeFirst()
                        onToken(reply)
                        return InferenceTelemetry(120, 0, 24, 10, 20, "fake-test")
                    }
                }
                val loop = AgentLoop(fakeEngine, ws, sql)
                val history = mutableListOf(HarnessMessage(HarnessMessage.Role.USER, "Total my expenses and write report.md"))
                val result = loop.runTurn(
                    model = ModelCatalog.byId("4B")!!,
                    modelPath = "/fake/model.gguf",
                    systemPrompt = AgentPrompt.local(mode = ChatMode.WORK),
                    history = history,
                    mode = ChatMode.WORK,
                    thinking = false,
                    allowedUrls = mutableSetOf(),
                    cancellation = InferenceCancellation()
                )

                assertEquals(2, result.steps.size)
                assertEquals("run_sql", result.steps[0].toolName)
                assertEquals("create_file", result.steps[1].toolName)
                assertEquals(1, result.proposedEdits.size)
                assertNull(ws.readText("report.md")) // Explicit approval required!
                assertTrue(result.finalReply.contains("23"))

                // User approves
                ws.applyProposal(result.proposedEdits.first())
                assertEquals("# Total\nTotal cost is 23.", ws.readText("report.md"))
            }
        }
    }
}
