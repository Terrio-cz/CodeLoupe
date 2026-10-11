package codeloupe.conventions

import codeloupe.lang.Languages
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

/** Reads the source files of a module's source sets and the facts of each; read-only, sources only (never a build file, resource or `.env`). */
object ModuleReader {
    private const val MAX_BYTES = 400_000L

    fun read(module: Module): List<ModuleFile> {
        val files = ArrayList<ModuleFile>()
        for (root in SourceSets.of(module.dir)) {
            Files.walk(root.dir).use { paths ->
                paths.filter { it.isRegularFile() && it.extension in EXTENSIONS && Files.size(it) <= MAX_BYTES }.sorted().forEach { path ->
                    val relative = module.dir.relativize(path).joinToString("/") { it.toString() }
                    // A file that is not valid UTF-8 is not source worth counting.
                    val text = try { Files.readString(path) } catch (e: java.nio.charset.CharacterCodingException) { return@forEach }
                    Languages.extract(relative, text)?.let { files += ModuleFile(relative, root.test, text, it) }
                }
            }
        }
        return files
    }

    /** Lines of main sources, to rank modules by size. */
    fun mainLines(module: Module): Int = SourceSets.of(module.dir).filter { !it.test }.sumOf { root ->
        Files.walk(root.dir).use { paths -> paths.filter { it.isRegularFile() && it.extension in EXTENSIONS }.mapToInt { lines(it) }.sum() }
    }

    /** Counted on bytes, so a file in another encoding is still a size and not a failure. */
    private fun lines(path: Path): Int = Files.readAllBytes(path).count { it == '\n'.code.toByte() }

    private val EXTENSIONS = setOf("kt", "java")
}
