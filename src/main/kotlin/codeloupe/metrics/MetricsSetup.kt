package codeloupe.metrics

import codeloupe.config.Config
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

/** What the `metrics` commands share: the categories and transcript directories from the configuration, and dates. */
class MetricsSetup(private val config: Config, private val userHome: Path = Path.of(System.getProperty("user.home"))) {
    fun categorizer(): Categorizer {
        val metrics = config.metrics
        return Categorizer(metrics.categories + if (metrics.defaultCategories) Categorizer.DEFAULT_RULES else emptyList())
    }

    /** The directories given, else those of the configuration, else every project under `~/.claude/projects`. */
    fun projectDirs(given: List<String>): List<Path> {
        val named = given.ifEmpty { config.metrics.transcriptDirs }
        if (named.isNotEmpty()) return named.map(Path::of)
        val projects = userHome.resolve(".claude").resolve("projects")
        return if (projects.isDirectory()) projects.listDirectoryEntries().filter { it.isDirectory() } else emptyList()
    }

    companion object {
        /** `2026-09-23` (midnight UTC) or a full ISO instant. */
        fun instant(text: String): Instant =
            runCatching { Instant.parse(text) }.getOrElse { LocalDate.parse(text).atStartOfDay().toInstant(ZoneOffset.UTC) }
    }
}
