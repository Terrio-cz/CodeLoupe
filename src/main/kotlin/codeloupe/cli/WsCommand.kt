package codeloupe.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands

/** `codeloupe ws …` — create Docker resources owned by the workspace of the current directory, and list who owns what. */
class WsCommand : CliktCommand(name = "ws") {
    init {
        subcommands(WsUpCommand(), WsRunCommand(), WsBuildCommand(), WsVolumeCommand(), WsResourcesCommand(), WsReconcileCommand())
    }

    override fun help(context: Context) =
        "Docker resources labelled with their workspace (codeloupe.repo, codeloupe.workspace, codeloupe.task): up, run, build, volume create; resources lists the owners, reconcile cleans released workspaces."

    override fun run() = Unit
}
