package codeloupe.docker

/**
 * The named volumes a `docker run` command line mounts (`-v name:/path`, `--volume=name:/path`, `--mount type=volume,source=name,…`).
 * Docker creates a missing one unlabelled, so `ws run` creates them first through the API. Bind mounts (a path as the source),
 * anonymous volumes and tmpfs are not named volumes.
 */
object NamedVolumes {
    fun of(args: List<String>): List<String> {
        val found = LinkedHashSet<String>()
        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when {
                arg == "-v" || arg == "--volume" -> args.getOrNull(++i)?.let { fromVolume(it)?.let(found::add) }
                arg.startsWith("--volume=") -> fromVolume(arg.removePrefix("--volume="))?.let(found::add)
                arg.startsWith("-v") && arg.length > 2 && !arg.startsWith("--") -> fromVolume(arg.removePrefix("-v"))?.let(found::add)
                arg == "--mount" -> args.getOrNull(++i)?.let { fromMount(it)?.let(found::add) }
                arg.startsWith("--mount=") -> fromMount(arg.removePrefix("--mount="))?.let(found::add)
            }
            i++
        }
        return found.toList()
    }

    // `name:/container/path[:options]`; a source that is a path (`/`, `./`, `~`, `C:\`) is a bind mount, no colon an anonymous volume.
    private fun fromVolume(spec: String): String? {
        val source = spec.substringBefore(':', missingDelimiterValue = "")
        return source.takeIf { it.length > 1 && it[0] !in "/.~\\" && VOLUME_NAME.matches(it) && ':' in spec }
    }

    private fun fromMount(spec: String): String? {
        val fields = spec.split(',').mapNotNull { f -> f.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()
        if ((fields["type"] ?: fields["Type"] ?: "volume") != "volume") return null
        return (fields["source"] ?: fields["src"])?.takeIf { VOLUME_NAME.matches(it) }
    }

    private val VOLUME_NAME = Regex("[a-zA-Z0-9][a-zA-Z0-9_.-]*")
}
