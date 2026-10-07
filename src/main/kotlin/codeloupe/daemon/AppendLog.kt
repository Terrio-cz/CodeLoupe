package codeloupe.daemon

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** A line log that rolls over to `<file>.1` past 10 MB. */
class AppendLog(private val file: Path) {
    @Synchronized
    fun append(line: String) {
        runCatching {
            if (Files.exists(file) && Files.size(file) > MAX_BYTES) {
                Files.move(file, Path.of("$file.1"), StandardCopyOption.REPLACE_EXISTING)
            }
            Files.writeString(file, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
    }

    private companion object {
        const val MAX_BYTES = 10L * 1024 * 1024
    }
}
