package codeloupe.cli

import codeloupe.docker.DockerCli
import codeloupe.docker.OwnedRun
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple

class WsRunCommand : WsOwnedCommand("run") {
    override val treatUnknownOptionsAsArgs = true

    private val args by argument(name = "RUN-ARGS", help = "Everything docker run takes, from its options to the command").multiple(required = true)

    override fun help(context: Context) =
        "docker run with the workspace's labels on the container; named volumes it mounts are created labelled. Own options (--dir) go first, then the docker run arguments."

    override fun run() {
        val owner = ownership()
        exit(OwnedRun(DockerCli(), ::engine, ::note).run(workDir, owner, args))
    }
}
