package codeloupe.jobs

import java.time.Duration
import java.time.Instant

/** The compact text an agent reads: one line per job, and for the job that matters its counts, failures and last lines. */
object JobReport {
    fun line(job: JobRecord, ahead: Int = 0): String {
        val state = when (job.status) {
            JobStatus.QUEUED -> job.slot?.let { "queued for $it" + if (ahead > 0) " ($ahead ahead)" else "" } ?: "starting"
            JobStatus.RUNNING -> "running for ${duration(since(job.startedAt))}"
            JobStatus.DONE -> "done exit ${job.exit} in ${duration(job.durationMs ?: 0)}"
            JobStatus.DENIED -> "denied: ${job.reason}"
            JobStatus.CANCELLED -> "cancelled" + (job.durationMs?.let { " after ${duration(it)}" } ?: "")
            JobStatus.LOST -> "lost (${job.reason})"
            JobStatus.ERROR -> "error: ${job.reason}"
        }
        val slot = job.slot?.takeIf { job.status != JobStatus.QUEUED }?.let { ", slot $it" }.orEmpty()
        val tag = job.tag?.let { ", $it" }.orEmpty()
        return "${job.id} $state$slot$tag: ${shorten(job.command, 120)}"
    }

    /** A chain (one job, or a job and its follow-ups) as `job wait` and `job status <id>` print it. */
    fun text(chain: List<JobRecord>): String = buildString {
        if (chain.size > 1) appendLine("chain: " + chain.joinToString(" -> ") { "${it.id} ${outcome(it)}" })
        val focus = chain.firstOrNull { it.status.terminal && !it.ok } ?: chain.last()
        appendLine(line(focus))
        focus.summary?.let { summary ->
            counts(summary)?.let(::appendLine)
            if (summary.failures.isNotEmpty()) {
                appendLine("failures:")
                summary.failures.forEach { appendLine("  $it") }
            }
            if (summary.tail.isNotEmpty()) {
                appendLine("last lines:")
                summary.tail.forEach { appendLine("  $it") }
            }
        }
        appendLine("log: ${focus.log}")
        if (focus !== chain.last()) appendLine("then: " + line(chain.last()))
    }.trimEnd()

    /** What `job wait` exits with: the exit code of the first failed job, 1 when it failed without one, 0 when all passed. */
    fun exitCode(chain: List<JobRecord>): Int {
        val failed = chain.firstOrNull { it.status.terminal && !it.ok } ?: return if (chain.lastOrNull()?.status?.terminal == true) 0 else 1
        return failed.exit?.takeIf { it != 0 } ?: 1
    }

    fun counts(summary: JobSummary): String? = summary.tests?.let { tests ->
        listOfNotNull("tests $tests", summary.failed?.let { "failed $it" }, summary.skipped?.takeIf { it > 0 }?.let { "skipped $it" }).joinToString(", ")
    }

    private fun outcome(job: JobRecord) = when (job.status) {
        JobStatus.DONE -> "exit ${job.exit}"
        else -> job.status.label
    }

    private fun since(iso: String?): Long = iso?.let { Duration.between(Instant.parse(it), Instant.now()).toMillis() } ?: 0

    fun duration(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 60 -> "$s s"
            s < 3600 -> "${s / 60} min ${s % 60} s"
            else -> "${s / 3600} h ${s % 3600 / 60} min"
        }
    }

    private fun shorten(text: String, max: Int) = if (text.length > max) text.take(max - 1) + "..." else text
}
