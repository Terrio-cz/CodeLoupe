package codeloupe.cli

import codeloupe.config.ConfigLoader
import codeloupe.secrets.SecretReport
import codeloupe.secrets.SecretStore
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option

class EnvListCommand : CliktCommand(name = "list") {
    private val workspace by option(help = "Only what applies in this workspace (folder or id)")
    private val repository by option("--repo", help = "...and in this repository")
    private val all by option("--all", help = "Every stored name of every scope").flag()

    override fun help(context: Context) = "Names, scopes, sources and last use of the stored secrets; never a value."

    override fun run() {
        val store = SecretStore.open(ConfigLoader.load().home)
        val metas = if (all || (workspace == null && repository == null)) store.list() else store.visible(SecretStore.chain(workspace, repository))
        if (metas.isEmpty()) return echo("no secrets stored")
        SecretReport.lines(metas).forEach(::echo)
    }
}
