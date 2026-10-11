package codeloupe.hooks.check

import codeloupe.config.HooksConfig.PostEditConfig
import codeloupe.hooks.HookInput
import codeloupe.hooks.HookText
import codeloupe.hooks.ShellPaths
import codeloupe.index.Extraction
import codeloupe.index.InlineParse
import codeloupe.lang.FileFacts
import codeloupe.platform.PathCase
import codeloupe.repo.Registry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/**
 * The answer to a `PostToolUse` call of `Edit`, `Write` or `MultiEdit` on a Kotlin or Java file the daemon has indexed: the file is
 * read and parsed, and a syntax error is told with its place; a file that parses is looked at by [NameCheck] (Kotlin only). Nothing is
 * said about a clean file. This is the parser and the index, not a compiler, so every line says what it is based on. Nothing here starts a build.
 */
class PostEditCheck(private val registry: Registry, private val paths: ShellPaths, private val ignoreCase: Boolean = PathCase.insensitive) {
    suspend fun check(input: HookInput, settings: PostEditConfig): PostEditOutcome {
        val cwd = input.cwd ?: return silent("ignored")
        val given = (input.toolInput["file_path"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return silent("ignored")
        val path = paths.resolve(cwd, given) ?: return silent("ignored")
        if (!SOURCE.containsMatchIn(path)) return silent("other-files")
        val file = Path.of(path)
        if (!Files.isRegularFile(file) || Files.size(file) > InlineParse.MAX_FILE_BYTES) return silent("not-indexed")
        val real = file.toRealPath()
        val location = registry.locate(real.parent.toString())
        registry.known(location.commonDir) ?: return silent("not-indexed")
        val relative = relative(location.worktree, real.toString()) ?: return silent("not-indexed")
        val name = relative.substringAfterLast('/')
        val facts = withContext(Dispatchers.IO) { Extraction.extract(path, Files.readString(file)) }
        if (facts.errors > 0) return report(name, "syntax", syntax(facts), settings)
        if (!path.endsWith(".kt", ignoreCase = true)) return silent("clean")
        registry.touched(location.worktree)
        val names = registry.query(location.worktree, speculative = false) { NameCheck(it).check(relative) }
        return if (names.isEmpty()) silent("clean") else report(name, "names", names.map { "${it.line}:${it.col} ${it.text}" }, settings)
    }

    private fun syntax(facts: FileFacts): List<String> {
        val lines = facts.problems.map { "${it.line}:${it.col} syntax error: ${it.message}" }
        return if (facts.errors > lines.size) lines + "(${facts.errors - lines.size} more syntax errors)" else lines
    }

    private fun report(name: String, kind: String, findings: List<String>, settings: PostEditConfig): PostEditOutcome {
        val room = settings.maxLines - 1
        val shown = if (findings.size <= room) findings else findings.take(room - 1) + "… and ${findings.size - room + 1} more"
        val header = "CodeLoupe check of ${HookText.line(name)} after the edit (parser and index, not a compiler):"
        return PostEditOutcome.Report(name, kind, findings.size, HookText.block((listOf(header) + shown).joinToString("\n")))
    }

    private fun relative(root: String, path: String): String? {
        val base = root.replace('\\', '/').trimEnd('/')
        val text = path.replace('\\', '/')
        if (!text.startsWith("$base/", ignoreCase = ignoreCase)) return null
        return text.substring(base.length + 1)
    }

    private fun silent(reason: String) = PostEditOutcome.Silent(reason)

    private companion object {
        val SOURCE = Regex("""(?i)\.(kt|java)$""")
    }
}
