package codeloupe.cli

import codeloupe.docker.ComposeUp
import codeloupe.docker.DockerCli
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option

class WsUpCommand : WsOwnedCommand("up") {
    override val treatUnknownOptionsAsArgs = true

    private val files by option("-f", "--file", help = "Compose file (repeat; default COMPOSE_FILE or compose.yaml in the directory)").multiple()
    private val project by option("-p", "--project", help = "Compose project name (default: repo and workspace)")
    private val profiles by option("--profile", help = "Compose profile to enable (repeat)").multiple()
    private val envFiles by option("--env-file", help = "Compose --env-file (repeat)").multiple()
    private val projectDirectory by option("--project-directory", help = "Compose --project-directory (where relative paths of the files resolve)")
    private val extra by argument(name = "UP-ARGS", help = "Arguments of docker compose up after the options above (default: -d)").multiple()

    override fun help(context: Context) =
        "docker compose up with the workspace's labels on the project's containers, built images, volumes and networks."

    override fun run() {
        val owner = ownership()
        val cli = DockerCli()
        exit(ComposeUp(cli, ::engine, ::note).up(workDir, owner, files, project, profiles, extra, envFiles, projectDirectory))
    }
}
