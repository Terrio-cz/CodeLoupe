package codeloupe.cli

import codeloupe.docker.DockerCli
import codeloupe.docker.OwnedBuild
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple

class WsBuildCommand : WsOwnedCommand("build") {
    override val treatUnknownOptionsAsArgs = true

    private val args by argument(name = "BUILD-ARGS", help = "Everything docker build takes, from its options to the context").multiple(required = true)

    override fun help(context: Context) =
        "docker build with the workspace's labels on the image, checked afterwards. Own options (--dir) go first, then the docker build arguments."

    override fun run() {
        val owner = ownership()
        exit(OwnedBuild(DockerCli(), ::engine, ::note).build(workDir, owner, args))
    }
}
