package codeloupe.ingest

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension

/**
 * Lists the transcripts under project directories (`~/.claude/projects/<project>`): sessions at the top, subagent runs under
 * `<session>/subagents/`. Size and modification time come with the directory walk, so a pass over thousands of files reads none.
 */
object TranscriptScanner {
    private const val SUBAGENTS = "subagents"
    private const val DEPTH = 3

    fun scan(projectDirs: List<Path>): List<FoundTranscript> {
        val found = ArrayList<FoundTranscript>()
        for (project in projectDirs.filter { it.isDirectory() }) {
            Files.walkFileTree(project, emptySet(), DEPTH, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val depth = if (dir == project) 0 else project.relativize(dir).nameCount
                    return if (depth == 2 && dir.name != SUBAGENTS) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (file.name.endsWith(".jsonl")) {
                        val depth = project.relativize(file).nameCount
                        val transcript = when {
                            depth == 1 -> FoundTranscript(file, "session", project.name, file.nameWithoutExtension, attrs.size(), attrs.lastModifiedTime().toMillis())
                            depth == DEPTH && file.parent.name == SUBAGENTS ->
                                FoundTranscript(file, "subagent", project.name, file.parent.parent.name, attrs.size(), attrs.lastModifiedTime().toMillis())
                            else -> null
                        }
                        transcript?.let(found::add)
                    }
                    return FileVisitResult.CONTINUE
                }

                // A file that disappears or cannot be read between listing and visiting is skipped, not fatal.
                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            })
        }
        return found
    }
}
