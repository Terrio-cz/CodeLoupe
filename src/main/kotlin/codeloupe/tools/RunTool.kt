package codeloupe.tools

import codeloupe.compress.OutputCompressor
import codeloupe.events.Scrubber
import codeloupe.jobs.JobReport
import codeloupe.jobs.JobRequest
import codeloupe.jobs.JobRunner
import codeloupe.jobs.Submission
import codeloupe.repo.Registry
import codeloupe.triage.Triage
import codeloupe.triage.ViewLocator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/**
 * `run`: a short command (git, gradle, a test run) that answers with a summary instead of its whole output. It is a job
 * (policy hook, log, scrubbing), so the full output stays under the handle `job:<id>` for `doc`; every error line is
 * kept in the summary or counted with a pointer to it.
 */
class RunTool(private val jobs: JobRunner) : Tool {
    override val name = "run"
    override val description = "Run a command that ends soon (git status / log / diff, a gradle build or test run, any CLI) and get a summary " +
        "instead of its output: git status as counts and names, git log one line per commit, diff --stat the totals and biggest files, " +
        "gradle as the build result, failed tests with first-party frames and compiler errors, other tests as counts and failures, anything " +
        "else its head, error lines and tail. Every error line survives or is counted. The header names the saving and the handle " +
        "job:<id>: read the rest with doc path=job:<id> (section=<handle>, L<from>-<to>). raw=true returns the whole output. Commands " +
        "pass the workspace policy hook; a command that runs long returns its job id (use job)."
    override val properties = Schema.properties(
        "command" to Schema.strings("Program and arguments (argv); no shell. Use [bash, -c, '…'] for pipes"),
        "cwd" to Schema.string("Directory to run in; default the caller's root"),
        "raw" to Schema.boolean("The whole output instead of a summary"),
        "timeoutSec" to Schema.integer(1, 900),
    )
    override val required = listOf("command")

    override suspend fun answer(registry: Registry, root: String, args: ToolArgs): String {
        val command = args.strings("command")
        require(command.isNotEmpty()) { "pass command: the program and its arguments" }
        val cwd = args.string("cwd")?.takeIf { it.isNotBlank() } ?: root
        val wait = (args.int("timeoutSec") ?: DEFAULT_WAIT).coerceIn(1, MAX_WAIT)
        val job = when (val submission = jobs.submit(JobRequest(command = command, cwd = cwd))) {
            is Submission.Accepted -> submission.job
            is Submission.Refused -> return "not started - the policy hook answered ${submission.decision.verdict.name.lowercase()}: ${submission.decision.reason}"
        }
        val ended = jobs.await(job.id, wait * 1000L).last()
        if (!ended.status.terminal) return "still running after ${wait}s: ${JobReport.line(ended)}; `codeloupe job wait ${job.id}` waits for it, doc path=job:${job.id} reads what it printed so far"
        val raw = withContext(Dispatchers.IO) { read(Path.of(ended.log)) }
        val text = Scrubber.text(raw)
        val exit = ended.exit?.let { "exit $it" } ?: ended.status.name.lowercase()
        if (args.bool("raw") == true) return "$exit\n$text"
        val result = OutputCompressor.compress(command, text, cwd)
        // A command that succeeded and printed little is only its output: no header to cost more than it saves.
        if (!result.shortened) return if (ended.exit == 0) result.text else listOf(exit, result.text).filter { it.isNotEmpty() }.joinToString("\n")
        val summary = if (ended.exit == 0) result.text else pointToDeclarations(registry, cwd, result.text)
        val saved = 100 - summary.length * 100 / text.length.coerceAtLeast(1)
        return "$exit · ${text.length} → ${summary.length} chars ($saved% less) · rest: doc path=job:${job.id}\n$summary"
    }

    /** The errors and failed tests of a summary with the declaration each is in; the summary as it is where the index cannot say. */
    private suspend fun pointToDeclarations(registry: Registry, cwd: String, summary: String): String = try {
        registry.query(cwd) { view -> Triage.apply(summary, ViewLocator(view)) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        summary
    }

    /** The log, its newest [MAX_BYTES] when it is larger: the end of an output is where a build says what went wrong. */
    private fun read(log: Path): String {
        if (!Files.isRegularFile(log)) return ""
        val size = Files.size(log)
        if (size <= MAX_BYTES) return String(Files.readAllBytes(log), Charsets.UTF_8)
        Files.newInputStream(log).use { input ->
            input.skipNBytes(size - MAX_BYTES)
            return "… first ${(size - MAX_BYTES) / 1024} KB of the output not read\n" + String(input.readAllBytes(), Charsets.UTF_8)
        }
    }

    private companion object {
        const val DEFAULT_WAIT = 120
        const val MAX_WAIT = 900
        const val MAX_BYTES = 4L * 1024 * 1024
    }
}
