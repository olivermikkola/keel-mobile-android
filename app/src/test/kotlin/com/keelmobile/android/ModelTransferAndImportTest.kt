package com.keelmobile.android

import com.keelmobile.android.data.ModelLibraryManager
import com.keelmobile.harness.LocalModel
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelTransferAndImportTest {
    private lateinit var tempDir: File
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("keel-model-test").toFile()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        tempDir.deleteRecursively()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun resumableRangeDownloadAndSha256Verification() = runTest {
        val payload = "0123456789abcdefghijklmnopqrstuvwxyz".toByteArray()
        val digest = sha256(payload)
        val model = LocalModel(
            id = "test-resume",
            name = "Test Resume",
            bytes = payload.size.toLong(),
            revision = "main",
            sha256 = digest,
            detail = "Test",
            fileName = "resume.gguf",
            sourceUrl = server.url("/resume.gguf").toString()
        )
        val mgr = ModelLibraryManager(tempDir)
        // Seed first 10 bytes as partial download
        mgr.partialFile(model.assets.first()).writeBytes(payload.copyOfRange(0, 10))

        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setBody(Buffer().write(payload.copyOfRange(10, payload.size)))
        )

        mgr.downloadModelAssets(model)
        val recorded = server.takeRequest()
        assertEquals("bytes=10-", recorded.getHeader("Range"))
        assertTrue(mgr.modelFilePath(model).exists())
        assertTrue(mgr.modelFilePath(model).readBytes().contentEquals(payload))
        assertFalse(mgr.partialFile(model.assets.first()).exists())
    }

    @Test
    fun ignoredRangeRestartsCleanlyInsteadOfAppendingDuplicates() = runTest {
        val payload = "full-fresh-model-payload".toByteArray()
        val digest = sha256(payload)
        val model = LocalModel(
            id = "test-restart",
            name = "Test Restart",
            bytes = payload.size.toLong(),
            revision = "main",
            sha256 = digest,
            detail = "Test",
            fileName = "restart.gguf",
            sourceUrl = server.url("/restart.gguf").toString()
        )
        val mgr = ModelLibraryManager(tempDir)
        mgr.partialFile(model.assets.first()).writeBytes("stale".toByteArray())

        // Server ignores Range and sends 200 with full body
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(Buffer().write(payload))
        )

        mgr.downloadModelAssets(model)
        assertTrue(mgr.modelFilePath(model).readBytes().contentEquals(payload))
    }

    @Test
    fun badSha256DeletesDamagedPartialFile() = runTest {
        val payload = "corrupted-bytes".toByteArray()
        val model = LocalModel(
            id = "test-badhash",
            name = "Bad Hash",
            bytes = payload.size.toLong(),
            revision = "main",
            sha256 = "0".repeat(64),
            detail = "Test",
            fileName = "bad.gguf",
            sourceUrl = server.url("/bad.gguf").toString()
        )
        val mgr = ModelLibraryManager(tempDir)
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(payload)))

        assertFailsWith<IllegalStateException> {
            mgr.downloadModelAssets(model)
        }
        assertFalse(mgr.modelFilePath(model).exists())
        assertFalse(mgr.partialFile(model.assets.first()).exists())
    }

    @Test
    fun importCustomGgufInspectsHeaderAndRejectsGarbage() = runTest {
        val mgr = ModelLibraryManager(tempDir)
        assertFailsWith<IllegalArgumentException> {
            mgr.importLocalStream("not-a-model.gguf") { ByteArrayInputStream("hello".toByteArray()) }
        }

        val out = ByteArrayOutputStream()
        fun writeInt(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        fun writeLong(v: Long) = out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array())
        fun writeStr(s: String) {
            val b = s.toByteArray(Charsets.UTF_8)
            writeLong(b.size.toLong())
            out.write(b)
        }
        out.write("GGUF".toByteArray(Charsets.US_ASCII))
        writeInt(3); writeLong(1L); writeLong(2L)
        writeStr("general.architecture"); writeInt(8); writeStr("lfm3")
        writeStr("general.name"); writeInt(8); writeStr("Custom-LFM3")
        val validBytes = out.toByteArray()

        val imported = mgr.importLocalStream("my-lfm3.gguf") { ByteArrayInputStream(validBytes) }
        assertEquals("Custom-LFM3", imported.name)
        assertTrue(imported.id in mgr.state.value.installedIds)
    }
}
