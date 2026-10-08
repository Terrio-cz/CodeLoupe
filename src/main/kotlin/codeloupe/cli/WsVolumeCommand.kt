package codeloupe.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands

class WsVolumeCommand : CliktCommand(name = "volume") {
    init {
        subcommands(WsVolumeCreateCommand())
    }

    override fun help(context: Context) = "Docker volumes owned by the workspace."

    override fun run() = Unit
}
