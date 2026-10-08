package codeloupe.docker

import java.io.InputStream
import java.io.OutputStream

/** One open connection to the Engine: the bytes in, the bytes out, and what closes them. */
class DockerConnection(val input: InputStream, val output: OutputStream, private val handle: AutoCloseable) : AutoCloseable {
    override fun close() = handle.close()
}
