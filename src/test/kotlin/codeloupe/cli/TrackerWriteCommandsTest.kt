package codeloupe.cli

import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.parse
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The argument rules of the tracker write verbs, which fail before any daemon is asked. */
class TrackerWriteCommandsTest {
    private fun usage(command: ToolCommand, vararg args: String) = assertFailsWith<UsageError> { command.parse(args.toList()) }.message.orEmpty()

    @Test
    fun `update takes one criterion edit and a text for a rewrite`() {
        assertContains(usage(UpdateCommand(), "CL-1", "--edit-criterion", "x"), "needs --to")
        assertContains(usage(UpdateCommand(), "CL-1", "--to", "x"), "only goes with --edit-criterion")
        assertContains(usage(UpdateCommand(), "CL-1", "--check", "a", "--edit-criterion", "b", "--to", "c"), "pass one")
    }

    @Test
    fun `create needs an epic and a summary and a readable description file`() {
        usage(CreateCommand(), "--summary", "x")
        usage(CreateCommand(), "--epic", "CL-1")
        assertContains(usage(CreateCommand(), "--epic", "CL-1", "--summary", "x", "--description-file", "no/such/file.md"), "no such file")
    }

    @Test
    fun `the verbs are registered under their names`() {
        assertEquals("attach", AttachCommand().commandName)
        assertEquals("create", CreateCommand().commandName)
    }
}
