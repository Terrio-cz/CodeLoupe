package codeloupe.cli

import codeloupe.config.ConfigLoader
import codeloupe.config.RepoConfig
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context

class ReposListCommand : CliktCommand(name = "list") {
    override fun help(context: Context) = "The repositories listed in config.json (workspaces.repos)."

    override fun run() {
        val repos = RepoConfig.list(ConfigLoader.load().home)
        if (repos.isEmpty()) echo("no repositories listed") else repos.forEach(::echo)
    }
}
