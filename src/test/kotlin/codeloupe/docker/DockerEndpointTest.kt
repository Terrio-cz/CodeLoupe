package codeloupe.docker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DockerEndpointTest {
    @Test
    fun `DOCKER_HOST names a named pipe or a socket`() {
        assertEquals(DockerEndpoint.NamedPipe("\\\\.\\pipe\\docker_engine"), DockerEndpoint.parse("npipe:////./pipe/docker_engine"))
        assertEquals(DockerEndpoint.UnixSocket("/var/run/docker.sock"), DockerEndpoint.parse("unix:///var/run/docker.sock"))
        assertFailsWith<DockerUnavailable> { DockerEndpoint.parse("tcp://127.0.0.1:2375") }
    }

    @Test
    fun `without DOCKER_HOST Windows tries the Docker Desktop pipes and the others the usual sockets`() {
        assertEquals(
            listOf(DockerEndpoint.NamedPipe("\\\\.\\pipe\\dockerDesktopLinuxEngine"), DockerEndpoint.NamedPipe("\\\\.\\pipe\\docker_engine")),
            DockerEndpoint.candidates(emptyMap(), "Windows 11"),
        )
        assertEquals(DockerEndpoint.UnixSocket("/var/run/docker.sock"), DockerEndpoint.candidates(emptyMap(), "Linux").first())
        assertEquals(listOf(DockerEndpoint.UnixSocket("/x.sock")), DockerEndpoint.candidates(mapOf("DOCKER_HOST" to "unix:///x.sock"), "Linux"))
    }
}
