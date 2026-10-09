package codeloupe.secrets

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** What the macOS backend asks `security` to do, with a fake in its place: the key must never be part of a command line. */
class KeychainCommandTest {
    private class Call(val command: List<String>, val stdin: String?)

    private val key = ByteArray(32) { (it * 11 + 3).toByte() }
    private val secret = Base64.getEncoder().encodeToString(key)

    private fun protector(calls: MutableList<Call>, exit: (Call) -> Int = { 0 }) =
        KeychainProtector("codeloupe-test") { command, stdin -> Call(command, stdin).also(calls::add).let { Exec.Result(exit(it), "") } }

    @Test
    fun `the key goes in on stdin and in no argument`() {
        val calls = mutableListOf<Call>()
        val account = protector(calls).wrap(key)
        assertTrue(calls.none { call -> call.command.any { secret in it } }, "the key is in an argument: ${calls.map { it.command }}")
        val add = calls.first { it.command == listOf("security", "-i") }
        assertContains(add.stdin.orEmpty(), "add-generic-password -a $account -s codeloupe-test -w $secret -U")
        assertTrue(add.stdin.orEmpty().endsWith("\n"))
    }

    @Test
    fun `a keychain that does not hold the item afterwards is an error`() {
        val calls = mutableListOf<Call>()
        val failing = protector(calls) { if (it.command.firstOrNull() == "security" && it.command.getOrNull(1) == "find-generic-password") 44 else 0 }
        assertFailsWith<IllegalStateException> { failing.wrap(key) }
        assertFailsWith<IllegalStateException> { protector(mutableListOf()) { 1 }.wrap(key) }
    }

    @Test
    fun `a service name that cannot be written plain is refused`() {
        assertFailsWith<IllegalArgumentException> { KeychainProtector("two words") { _, _ -> Exec.Result(0, "") } }
        assertEquals("keychain", protector(mutableListOf()).name)
    }
}
