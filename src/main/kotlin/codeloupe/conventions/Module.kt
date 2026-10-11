package codeloupe.conventions

import java.nio.file.Path

/** A build module: a directory with its own `src/<set>/(kotlin|java)` trees. [name] is Gradle-style (`:public-api`, `:importers:cuzk`). */
data class Module(val name: String, val dir: Path)
