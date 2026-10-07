package codeloupe.cli

import codeloupe.config.ConfigLoader
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument

class JobWaitCommand : CliktCommand(name = "wait") {
    private val id by argument(help = "Job id from `job start`")

    override fun help(context: Context) =
        "Block until the job and its follow-ups end, with no time limit; print the compact result and exit with the job's code. " +
            "Run it as a background task: one notification, whatever the duration."

    override fun run() {
        val (exit, text) = JobWaiter(DaemonClient(ConfigLoader.load())).wait(id)
        echo(text)
        if (exit != 0) throw ProgramResult(exit)
    }
}
