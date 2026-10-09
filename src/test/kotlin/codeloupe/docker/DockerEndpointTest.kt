package codeloupe.docker

import codeloupe.TestRepos
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DockerEndpointTest {
    private val home = TestRepos.tmpDir("docker-home")

    private fun unix(vararg paths: String) = paths.map(DockerEndpoint::UnixSocket)

    private fun candidates(env: Map<String, String> = emptyMap(), os: String = "Linux", uid: Long? = 1000) = DockerEndpoint.candidates(env, os, home.toString().replace('\\', '/'), uid)

    // The Docker CLI keeps a context under contexts/meta/<sha256 of its name>/meta.json.
    private fun context(name: String, host: String, current: Boolean = true, config: Path = home.resolve(".docker")) {
        val digest = MessageDigest.getInstance("SHA-256").digest(name.toByteArray()).toHexString()
        config.resolve("contexts/meta/$digest").createDirectories().resolve("meta.json").writeText("""{"Name":"$name","Endpoints":{"docker":{"Host":"$host"}}}""")
        if (current) config.createDirectories().resolve("config.json").writeText("""{"auths":{},"currentContext":"$name"}""")
    }

    @Test
    fun `DOCKER_HOST names a named pipe or a socket`() {
        assertEquals(DockerEndpoint.NamedPipe("\\\\.\\pipe\\docker_engine"), DockerEndpoint.parse("npipe:////./pipe/docker_engine"))
        assertEquals(DockerEndpoint.NamedPipe("\\\\.\\pipe\\docker_engine"), DockerEndpoint.parse("npipe://./pipe/docker_engine"))
        assertEquals(DockerEndpoint.UnixSocket("/var/run/docker.sock"), DockerEndpoint.parse("unix:///var/run/docker.sock"))
        assertEquals(DockerEndpoint.UnixSocket("/run/user/1000/docker.sock"), DockerEndpoint.parse("unix:///run/user/1000/docker.sock"))
        assertFailsWith<DockerUnavailable> { DockerEndpoint.parse("tcp://127.0.0.1:2375") }
        assertFailsWith<DockerUnavailable> { DockerEndpoint.parse("ssh://me@build-box") }
        assertFailsWith<DockerUnavailable> { DockerEndpoint.parse("/var/run/docker.sock") }
    }

    @Test
    fun `DOCKER_HOST alone decides, with the spaces a shell may leave around it`() {
        assertEquals(unix("/x.sock"), candidates(mapOf("DOCKER_HOST" to "unix:///x.sock")))
        assertEquals(unix("/x.sock"), candidates(mapOf("DOCKER_HOST" to "  unix:///x.sock\n")))
        context("colima", "unix:///elsewhere.sock")
        assertEquals(unix("/x.sock"), candidates(mapOf("DOCKER_HOST" to "unix:///x.sock")), "the variable beats the context, as for the docker CLI")
        assertFailsWith<DockerUnavailable> { candidates(mapOf("DOCKER_HOST" to "tcp://localhost:2375")) }
    }

    @Test
    fun `Windows tries the Docker Desktop pipes`() {
        assertEquals(
            listOf(DockerEndpoint.NamedPipe("\\\\.\\pipe\\dockerDesktopLinuxEngine"), DockerEndpoint.NamedPipe("\\\\.\\pipe\\docker_engine")),
            candidates(os = "Windows 11"),
        )
    }

    @Test
    fun `Linux and macOS try Docker Desktop, rootless Docker, Colima, OrbStack, Rancher Desktop and Podman in that order`() {
        val h = home.toString().replace('\\', '/')
        assertEquals(
            unix(
                "/var/run/docker.sock", "$h/.docker/run/docker.sock", "$h/.docker/desktop/docker.sock", "/run/user/1000/docker.sock",
                "$h/.colima/default/docker.sock", "$h/.colima/docker.sock", "$h/.orbstack/run/docker.sock", "$h/.rd/docker.sock",
                "/run/user/1000/podman/podman.sock", "/run/podman/podman.sock",
            ),
            candidates(),
        )
        assertEquals(unix("/var/run/docker.sock").first(), candidates(os = "Mac OS X").first())
    }

    @Test
    fun `the runtime directory comes from the environment first, and is left out when nothing names it`() {
        val env = mapOf("XDG_RUNTIME_DIR" to "/run/custom")
        assertEquals(true, candidates(env).contains(DockerEndpoint.UnixSocket("/run/custom/docker.sock")))
        assertEquals(true, candidates(env).contains(DockerEndpoint.UnixSocket("/run/custom/podman/podman.sock")))
        assertEquals(false, candidates(uid = null).any { it.address.startsWith("/run/user") })
    }

    @Test
    fun `the Docker context in use comes before the usual sockets, whether config json or DOCKER_CONTEXT names it`() {
        context("colima", "unix:///Users/me/.colima/custom/docker.sock")
        assertEquals(DockerEndpoint.UnixSocket("/Users/me/.colima/custom/docker.sock"), candidates().first())
        assertEquals(DockerEndpoint.UnixSocket("/var/run/docker.sock"), candidates().drop(1).first())
        context("orb", "unix:///Users/me/.orbstack/run/docker.sock", current = false)
        assertEquals(DockerEndpoint.UnixSocket("/Users/me/.orbstack/run/docker.sock"), candidates(mapOf("DOCKER_CONTEXT" to "orb")).first())
    }

    @Test
    fun `DOCKER_CONFIG moves the place the contexts are read from`() {
        val config = TestRepos.tmpDir("docker-config")
        context("remote-sock", "unix:///srv/docker.sock", config = config)
        assertEquals(DockerEndpoint.UnixSocket("/srv/docker.sock"), candidates(mapOf("DOCKER_CONFIG" to config.toString())).first())
        assertEquals(DockerEndpoint.UnixSocket("/var/run/docker.sock"), candidates().first(), "the home's own docker directory has no context")
    }

    @Test
    fun `the default context, a context that is not a local socket and unreadable files add nothing`() {
        assertNull(DockerSockets.contextHost(emptyMap(), home.toString()))
        context("default", "unix:///ignored.sock")
        assertNull(DockerSockets.contextHost(emptyMap(), home.toString()))
        context("cloud", "tcp://10.0.0.5:2376")
        assertNull(DockerSockets.contextHost(emptyMap(), home.toString()))
        context("ssh-box", "ssh://me@box")
        assertNull(DockerSockets.contextHost(emptyMap(), home.toString()))
        home.resolve(".docker/config.json").writeText("{ not json")
        assertEquals(unix("/var/run/docker.sock"), candidates().take(1))
        Files.delete(home.resolve(".docker/config.json"))
        assertEquals(unix("/var/run/docker.sock"), candidates().take(1))
    }

    @Test
    fun `a Windows named pipe context is found too`() {
        context("desktop-linux", "npipe:////./pipe/dockerDesktopLinuxEngine")
        assertEquals(DockerEndpoint.NamedPipe("\\\\.\\pipe\\dockerDesktopLinuxEngine"), candidates(os = "Windows 11").first())
        assertEquals(2, candidates(os = "Windows 11").size, "the same pipe is not tried twice")
    }
}
