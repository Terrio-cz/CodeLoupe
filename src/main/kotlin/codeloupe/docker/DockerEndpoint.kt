package codeloupe.docker

import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Path

/** Where the Docker Engine listens: a Windows named pipe or a unix socket. TCP is not supported. */
sealed interface DockerEndpoint {
    val address: String

    fun connect(): DockerConnection

    data class NamedPipe(override val address: String) : DockerEndpoint {
        override fun connect(): DockerConnection {
            val pipe = open()
            return DockerConnection(
                object : InputStream() {
                    override fun read(): Int = pipe.read()
                    override fun read(b: ByteArray, off: Int, len: Int): Int = pipe.read(b, off, len)
                },
                object : OutputStream() {
                    override fun write(b: Int) = pipe.write(b)
                    override fun write(b: ByteArray, off: Int, len: Int) = pipe.write(b, off, len)
                },
                pipe,
            )
        }

        // Every instance of a pipe is busy while the Engine serves other clients (ERROR_PIPE_BUSY, which Java reports as a
        // plain FileNotFoundException): it frees up within milliseconds, so opening is retried before giving up.
        private fun open(): RandomAccessFile {
            var attempt = 0
            while (true) {
                try {
                    return RandomAccessFile(address, "rw")
                } catch (e: FileNotFoundException) {
                    if (++attempt >= PIPE_ATTEMPTS) throw e
                    Thread.sleep(PIPE_RETRY_MS)
                }
            }
        }
    }

    data class UnixSocket(override val address: String) : DockerEndpoint {
        override fun connect(): DockerConnection {
            val channel = SocketChannel.open(UnixDomainSocketAddress.of(Path.of(address)))
            return DockerConnection(Channels.newInputStream(channel), Channels.newOutputStream(channel), channel)
        }
    }

    companion object {
        private const val PIPE_ATTEMPTS = 40
        private const val PIPE_RETRY_MS = 25L
        private val WINDOWS_PIPES = listOf("//./pipe/dockerDesktopLinuxEngine", "//./pipe/docker_engine")

        /** `DOCKER_HOST` when set, otherwise the usual local sockets of the platform, in the order Docker Desktop prefers them. */
        fun candidates(env: Map<String, String> = System.getenv(), os: String = System.getProperty("os.name")): List<DockerEndpoint> {
            env["DOCKER_HOST"]?.takeIf { it.isNotBlank() }?.let { return listOf(parse(it)) }
            if (os.lowercase().startsWith("windows")) return WINDOWS_PIPES.map { NamedPipe(it.replace('/', '\\')) }
            val home = System.getProperty("user.home")
            return listOf("/var/run/docker.sock", "$home/.docker/run/docker.sock", "$home/.docker/desktop/docker.sock").map(::UnixSocket)
        }

        fun parse(host: String): DockerEndpoint = when {
            host.startsWith("npipe://") -> NamedPipe(host.removePrefix("npipe://").replace('/', '\\'))
            host.startsWith("unix://") -> UnixSocket(host.removePrefix("unix://"))
            else -> throw DockerUnavailable("DOCKER_HOST=$host: only npipe:// and unix:// endpoints are supported")
        }

        /** The first candidate that accepts a connection; requests open their own connections. */
        fun firstReachable(candidates: List<DockerEndpoint>): DockerEndpoint {
            val failures = ArrayList<String>()
            for (endpoint in candidates) {
                try {
                    endpoint.connect().close()
                    return endpoint
                } catch (e: IOException) {
                    failures += "${endpoint.address}: ${e.message.orEmpty().lineSequence().first()}"
                }
            }
            throw DockerUnavailable("no Docker Engine endpoint answers (${failures.joinToString("; ")})")
        }
    }
}
