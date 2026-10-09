package codeloupe.docker

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Where a Docker Engine is when nothing says so: the endpoint of the Docker context in use (`DOCKER_CONTEXT`, else `currentContext` of
 * `config.json` in `DOCKER_CONFIG` or `~/.docker`), then the sockets of Docker Desktop, rootless Docker, Colima, OrbStack, Rancher Desktop and Podman.
 * Pure over the environment and the home directory, so every platform's list is testable on any machine.
 */
internal object DockerSockets {
    /** The unix socket paths to try, in order; the Windows pipes are [DockerEndpoint]'s own. */
    fun unix(env: Map<String, String>, home: String, uid: Long?): List<String> {
        val runtime = env["XDG_RUNTIME_DIR"]?.takeIf { it.isNotBlank() } ?: uid?.let { "/run/user/$it" }
        return buildList {
            add("/var/run/docker.sock")
            add("$home/.docker/run/docker.sock")
            add("$home/.docker/desktop/docker.sock")
            runtime?.let { add("$it/docker.sock") }
            add("$home/.colima/default/docker.sock")
            add("$home/.colima/docker.sock")
            add("$home/.orbstack/run/docker.sock")
            add("$home/.rd/docker.sock")
            runtime?.let { add("$it/podman/podman.sock") }
            add("/run/podman/podman.sock")
        }.distinct()
    }

    /** The `unix://` or `npipe://` host of the Docker context in use, or null for the default context or one that is not a local socket. */
    fun contextHost(env: Map<String, String>, home: String): String? {
        val config = env["DOCKER_CONFIG"]?.takeIf { it.isNotBlank() }?.let(Path::of) ?: Path.of(home, ".docker")
        val name = env["DOCKER_CONTEXT"]?.takeIf { it.isNotBlank() } ?: currentContext(config) ?: return null
        if (name == "default") return null
        val meta = config.resolve("contexts").resolve("meta").resolve(sha256(name)).resolve("meta.json")
        val host = json(meta)?.get("Endpoints")?.jsonObject?.get("docker")?.jsonObject?.get("Host")?.jsonPrimitive?.content
        return host?.takeIf { it.startsWith("unix://") || it.startsWith("npipe://") }
    }

    // The Docker CLI names a context's directory after the SHA-256 of its name.
    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).toHexString()

    private fun currentContext(config: Path): String? = json(config.resolve("config.json"))?.get("currentContext")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

    private fun json(file: Path): JsonObject? = runCatching { Json.parseToJsonElement(Files.readString(file)).jsonObject }.getOrNull()
}
