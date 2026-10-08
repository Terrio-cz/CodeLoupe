package codeloupe.cli

import kotlin.test.Test
import kotlin.test.assertEquals

class CodeLoupeCommandTest {
    private fun names(requested: String?) = CodeLoupeCommand(requested).registeredSubcommands().map { it.commandName }

    @Test
    fun `every subcommand is registered under its own name`() {
        assertEquals(CodeLoupeCommand.COMMANDS.map { it.first }, CodeLoupeCommand.COMMANDS.map { it.second().commandName })
        assertEquals(CodeLoupeCommand.COMMANDS.size, CodeLoupeCommand.COMMANDS.map { it.first }.toSet().size)
    }

    @Test
    fun `a named subcommand is the only one built`() {
        assertEquals(listOf("find"), names("find"))
        assertEquals(listOf("webhook"), names("webhook"))
    }

    @Test
    fun `help, a typo or no word at all builds every subcommand`() {
        val all = CodeLoupeCommand.COMMANDS.map { it.first }
        assertEquals(all, names(null))
        assertEquals(all, names("--help"))
        assertEquals(all, names("fnid"))
    }
}
