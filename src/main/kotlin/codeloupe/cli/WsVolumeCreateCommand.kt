package codeloupe.cli

import codeloupe.docker.DockerApi
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument

class WsVolumeCreateCommand : WsOwnedCommand("create") {
    private val volume by argument(name = "NAME")

    override fun help(context: Context) =
        "Create a volume with the workspace's labels. An existing volume of the workspace is left as it is; one that is not the workspace's is an error and stays untouched."

    override fun run() {
        val owner = ownership()
        when (engine().createVolume(volume, owner)) {
            DockerApi.VolumeOutcome.CREATED -> echo(volume)
            DockerApi.VolumeOutcome.ALREADY_OURS -> echo(volume)
            DockerApi.VolumeOutcome.EXISTS_OTHER -> fail("volume $volume already exists and is not owned by workspace ${owner.workspace}; not touched")
        }
    }
}
