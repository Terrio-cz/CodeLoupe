package codeloupe.cli

import codeloupe.platform.IsoTime
import java.nio.file.Files
import java.nio.file.Path

/**
 * `<home>/stopped`: tells the desktop app that the daemon was stopped on purpose, so it does not start it again. `codeloupe stop`
 * writes it, `codeloupe start` removes it (docs/ui-spec.md § 8).
 */
object StopMarker {
    fun file(home: Path): Path = home.resolve("stopped")

    fun write(home: Path) {
        Files.createDirectories(home)
        Files.writeString(file(home), IsoTime.now() + "\n")
    }

    fun clear(home: Path) {
        Files.deleteIfExists(file(home))
    }
}
