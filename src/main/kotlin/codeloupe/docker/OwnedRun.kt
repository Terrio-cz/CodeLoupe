package codeloupe.docker

import java.nio.file.Path

/**
 * `docker run` with the ownership labels on the container. The named volumes it mounts are created first through the
 * API, labelled, because Docker would create them without; one that already exists is mounted as it is.
 */
class OwnedRun(private val cli: DockerCli, private val api: () -> DockerApi, private val note: (String) -> Unit) {
    /** [args] are the arguments of `docker run`, from its options to the command. Answers the exit code of `docker run`. */
    fun run(dir: Path, ownership: Ownership, args: List<String>): Int {
        OwnLabels.rejectOwn(args)
        val volumes = NamedVolumes.of(args)
        if (volumes.isNotEmpty()) {
            val engine = api()
            for (name in volumes) {
                if (engine.createVolume(name, ownership) == DockerApi.VolumeOutcome.EXISTS_OTHER) note("volume $name exists and is not this workspace's; mounted as it is, not relabelled")
            }
        }
        return cli.run(listOf("run") + OwnLabels.flags(ownership) + args, dir)
    }
}
