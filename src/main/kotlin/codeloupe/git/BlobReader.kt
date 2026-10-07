package codeloupe.git

import codeloupe.platform.TimedPart
import codeloupe.platform.Timings
import java.io.BufferedInputStream
import java.io.InputStream

/** Streams blob contents through one `git cat-file --batch`, one blob in memory at a time. */
object BlobReader {
    fun of(cwd: String) = BlobSource { shas, onBlob -> read(cwd, shas, onBlob) }

    /** Calls [onBlob] with each blob's UTF-8 text in request order; returns how many blobs were read. */
    fun read(cwd: String, shas: Collection<String>, onBlob: (sha: String, text: String) -> Unit): Int = Timings.measure(TimedPart.GIT) {
        val process = Git.start(cwd, "cat-file", "--batch")
        // A separate writer keeps the request pipe from filling up while we read answers.
        val writer = Thread.ofVirtual().start {
            runCatching { process.outputStream.bufferedWriter().use { w -> shas.forEach { w.write(it); w.write("\n") } } }
        }
        val input = BufferedInputStream(process.inputStream, 1 shl 16)
        var count = 0
        try {
            while (true) {
                val header = readLine(input) ?: break
                val parts = header.split(' ')
                if (parts.size < 3 || parts[1] == "missing") continue
                val bytes = input.readNBytes(parts[2].toInt())
                input.read()
                count++
                onBlob(parts[0], bytes.toString(Charsets.UTF_8))
            }
        } catch (e: Throwable) {
            process.destroy()
            throw e
        } finally {
            writer.join()
        }
        val code = process.waitFor()
        if (code != 0) throw GitException("git cat-file exit $code")
        count
    }

    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (line.isEmpty()) null else line.toString()
            if (c == '\n'.code) return line.toString()
            line.append(c.toChar())
        }
    }
}
