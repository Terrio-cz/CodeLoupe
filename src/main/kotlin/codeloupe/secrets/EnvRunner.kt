package codeloupe.secrets

import codeloupe.events.Scrubber
import java.io.BufferedReader
import java.io.InputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets

/** Starts a command with the secrets that apply to a workspace and repository in its environment, and masks every stored value in what it prints. */
class EnvRunner(private val store: SecretStore) {
    /** The command's exit code; its output goes to [out] and [err] line by line, masked. */
    fun run(command: List<String>, workspace: String?, repository: String?, out: PrintStream, err: PrintStream): Int {
        require(command.isNotEmpty()) { "no command" }
        val program = command.first().substringAfterLast('/').substringAfterLast('\\')
        val values = store.resolve(SecretStore.chain(workspace, repository), usedBy = "env run: $program")
        val known = store.knownValues()
        val builder = ProcessBuilder(command).redirectInput(ProcessBuilder.Redirect.INHERIT)
        builder.environment().remove(KeyProtectors.PASSPHRASE_VARIABLE)
        builder.environment().putAll(values.mapValues { it.value.value })
        val process = builder.start()
        val pumps = listOf(pump(process.inputStream, out, known), pump(process.errorStream, err, known))
        val exit = process.waitFor()
        pumps.forEach { it.join() }
        return exit
    }

    /** Line by line, so a value cannot hide between two reads. */
    private fun pump(from: InputStream, to: PrintStream, known: Collection<String>): Thread = Thread {
        BufferedReader(from.reader(StandardCharsets.UTF_8)).forEachLine { to.println(Scrubber.mask(it, known)) }
    }.apply { start() }
}
