package codeloupe.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands

/** `codeloupe know …` — the knowledge store of the daemon: facts with scope, kind and code anchors, searched by relevance. */
class KnowCommand : CliktCommand(name = "know") {
    init {
        subcommands(KnowSearchCommand(), KnowPutCommand(), KnowShowCommand(), KnowStaleCommand(), KnowVerifyCommand(), KnowImportCommand())
    }

    override fun help(context: Context) =
        "Facts agents learned, kept with their scope and the code they hold for: search, write, find the stale ones, import brain notation files."

    override fun run() = Unit
}
