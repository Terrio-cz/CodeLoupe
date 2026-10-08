package codeloupe.jobs

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Rewrites a finished job's log with the stored secret values replaced by `***`, so the file an agent may read holds
 * none. Byte exact apart from the values; one line at a time, because the daemon's heap is small and a log can be huge.
 * A multi-line secret (a key file) is masked line by line.
 */
object LogMask {
    private const val MASK = "***"
    private const val MIN = 6
    private const val MAX_LINE = 16 * 1024 * 1024

    fun apply(log: Path, secrets: Collection<String>) {
        val needles = secrets.flatMap { it.lines() + it }.map { it.trim('\r') }.filter { it.length >= MIN }.distinct().sortedByDescending { it.length }
            .map { String(it.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1) to MASK }
        if (needles.isEmpty() || !Files.isRegularFile(log)) return
        val temp = Files.createTempFile(log.parent, log.fileName.toString(), ".mask")
        try {
            var changed = false
            BufferedInputStream(Files.newInputStream(log), 64 * 1024).use { input ->
                BufferedOutputStream(Files.newOutputStream(temp), 64 * 1024).use { output ->
                    val line = java.io.ByteArrayOutputStream()
                    while (true) {
                        val b = input.read()
                        if (b != -1) line.write(b)
                        if (b == -1 || b == '\n'.code || line.size() >= MAX_LINE) {
                            if (line.size() > 0) {
                                val text = line.toString(Charsets.ISO_8859_1)
                                val masked = needles.fold(text) { acc, (needle, mask) -> if (needle in acc) acc.replace(needle, mask) else acc }
                                if (masked !== text && masked != text) changed = true
                                output.write(masked.toByteArray(Charsets.ISO_8859_1))
                                line.reset()
                            }
                            if (b == -1) break
                        }
                    }
                }
            }
            if (changed) Files.move(temp, log, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}
