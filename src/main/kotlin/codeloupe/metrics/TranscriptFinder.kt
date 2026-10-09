package codeloupe.metrics

import codeloupe.platform.BoundedRead
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText

/**
 * The transcripts of project directories (`~/.claude/projects/<project>`): sessions at the top, subagent runs under
 * `<session>/subagents/` with a `.meta.json` naming the agent type. A file counts when it was written since [since]
 * and created before [until].
 */
class TranscriptFinder(private val since: Instant?, private val until: Instant?) {
    fun find(projectDirs: List<Path>): List<TranscriptFile> = projectDirs.filter { it.isDirectory() }.flatMap { project ->
        project.listDirectoryEntries().sortedBy { it.name }.flatMap { entry ->
            when {
                entry.extension == "jsonl" && fresh(entry) -> listOf(TranscriptFile(entry, "session", "main"))
                entry.isDirectory() -> subagents(entry)
                else -> emptyList()
            }
        }
    }

    private fun subagents(session: Path): List<TranscriptFile> {
        val dir = session.resolve("subagents")
        if (!dir.isDirectory()) return emptyList()
        return dir.listDirectoryEntries("*.jsonl").sortedBy { it.name }.filter(::fresh).map { file ->
            val meta = BoundedRead.text(file.resolveSibling("${file.nameWithoutExtension}.meta.json"))?.let { text -> runCatching { Json.parseToJsonElement(text).obj() }.getOrNull() }
            val description = meta?.get("description").str().orEmpty()
            TranscriptFile(file, "subagent", meta?.get("agentType").str() ?: "unknown", TER.find(description)?.value)
        }
    }

    private fun fresh(file: Path): Boolean {
        val attrs = Files.readAttributes(file, BasicFileAttributes::class.java)
        return (since == null || attrs.lastModifiedTime().toInstant() >= since) && (until == null || attrs.creationTime().toInstant() <= until)
    }

    private companion object {
        val TER = Regex("TER-\\d+")
    }
}
