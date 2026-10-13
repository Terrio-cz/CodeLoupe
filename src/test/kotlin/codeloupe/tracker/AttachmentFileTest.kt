package codeloupe.tracker

import codeloupe.TestRepos
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class AttachmentFileTest {
    private val dir = TestRepos.tmpDir("attachment")

    @Test
    fun `a small ordinary file is read`() {
        assertContentEquals("ok".toByteArray(), AttachmentFile.read(Files.writeString(dir.resolve("report.md"), "ok")))
    }

    @Test
    fun `secrets by name, big and missing files are refused`() {
        listOf(".env", ".env.local", "prod.env", "id.pem", "tls.KEY", "a.p12", "a.pfx").forEach { name ->
            assertContains(assertFailsWith<TrackerException> { AttachmentFile.read(Files.writeString(dir.resolve(name), "x")) }.message!!, "looks like a secret")
        }
        Files.write(dir.resolve("big.bin"), ByteArray((AttachmentFile.MAX_BYTES + 1).toInt()))
        assertContains(assertFailsWith<TrackerException> { AttachmentFile.read(dir.resolve("big.bin")) }.message!!, "limit")
        assertContains(assertFailsWith<TrackerException> { AttachmentFile.read(dir.resolve("none.md")) }.message!!, "no such file")
        assertContains(assertFailsWith<TrackerException> { AttachmentFile.read(dir) }.message!!, "no such file")
    }
}
