package codeloupe.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands

/** `codeloupe job …` — long commands run by the daemon, detached from the agent session. */
class JobCommand : CliktCommand(name = "job") {
    init {
        subcommands(JobStartCommand(), JobWaitCommand(), JobStatusCommand(), JobCancelCommand())
    }

    override fun help(context: Context) =
        "Run long commands in the daemon instead of waiting on them: start, then end the turn; `job wait` in a background task wakes you once."

    override fun run() = Unit
}
