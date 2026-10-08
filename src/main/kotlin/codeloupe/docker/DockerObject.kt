package codeloupe.docker

/**
 * A container, image, volume or network as the Engine lists it. [names] are what an adoption rule can match: container
 * names, image `repo:tag`s, the volume or network name.
 */
data class DockerObject(
    val kind: ResourceKind,
    val id: String,
    val names: List<String>,
    val labels: Map<String, String>,
    /** Containers: running, exited, …; nothing for the other kinds. */
    val state: String? = null,
    val created: String? = null,
) {
    /** The compose project that created it; its name is matched by adoption rules too. */
    val project: String? get() = labels[COMPOSE_PROJECT]?.takeIf { it.isNotBlank() }

    companion object {
        const val COMPOSE_PROJECT = "com.docker.compose.project"
    }
}
