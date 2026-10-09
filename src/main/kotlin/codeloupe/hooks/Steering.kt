package codeloupe.hooks

import codeloupe.hooks.ShellIntent.Filter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Decides whether a `Bash`, `PowerShell` or `Read` call is a search or a whole-file read that a CodeLoupe tool answers,
 * and with which call. Pure apart from what [sources] says about the index; [Hooks] adds the mode, the repeat guard and the log.
 */
class Steering(private val sources: SourceFiles, private val paths: ShellPaths) {
    private val intents = ShellIntents(paths)

    /** The advice for the call, or null to leave it alone. */
    fun advise(tool: String, input: JsonObject, cwd: String, minLines: Int): Advice? = (judge(tool, input, cwd, minLines) as? Verdict.Advise)?.advice

    fun judge(tool: String, input: JsonObject, cwd: String, minLines: Int): Verdict = when (tool) {
        "Read" -> read(input, cwd, minLines)
        "Bash", "PowerShell" ->
            (input["command"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { shell(it, cwd, minLines, if (tool == "PowerShell") ShellDialect.POWERSHELL else ShellDialect.POSIX) }
                ?: skip(Verdict.NOT_A_SEARCH)
        else -> skip(Verdict.NOT_A_SEARCH)
    }

    private fun skip(reason: String) = Verdict.Skip(reason)

    private fun advise(advice: Advice, anchor: String) = Verdict.Advise(advice, anchor)

    private fun read(input: JsonObject, cwd: String, minLines: Int): Verdict {
        if (input["offset"] != null || input["limit"] != null) return skip(Verdict.SMALL)
        val path = (input["file_path"] as? JsonPrimitive)?.content ?: return skip(Verdict.NOT_A_SEARCH)
        return wholeFile(paths.resolve(cwd, path) ?: return skip(Verdict.NOT_INDEXED), minLines)
    }

    private fun shell(command: String, cwd: String, minLines: Int, dialect: ShellDialect): Verdict {
        var furthest: Verdict.Skip = skip(Verdict.NOT_A_SEARCH)
        for (intent in intents.parse(command, cwd, dialect)) {
            val verdict = when (intent) {
                is ShellIntent.Search -> search(intent)
                is ShellIntent.FileList -> fileList(intent)
                is ShellIntent.Read -> partOfFile(intent, minLines)
            }
            if (verdict is Verdict.Advise) return verdict
            if (verdict is Verdict.Skip && verdict.rank > furthest.rank) furthest = verdict
        }
        return furthest
    }

    private fun partOfFile(intent: ShellIntent.Read, minLines: Int): Verdict {
        if (!SourceNames.isSource(intent.path)) return skip(Verdict.OTHER_FILES)
        val file = sources.file(intent.path) ?: return skip(Verdict.NOT_INDEXED)
        return if (intent.lines(file.lines) >= minLines) advise(outline(file), intent.path) else skip(Verdict.SMALL)
    }

    private fun wholeFile(path: String, minLines: Int): Verdict {
        if (!SourceNames.isSource(path)) return skip(Verdict.OTHER_FILES)
        val file = sources.file(path) ?: return skip(Verdict.NOT_INDEXED)
        return if (file.lines >= minLines) advise(outline(file), path) else skip(Verdict.SMALL)
    }

    private fun outline(file: SourceFile) = Advice(
        "outline", listOf("target" to file.relative), "read", strong = true,
        note = "the file has ${file.lines} lines; `symbol name=\"Type.member\"` returns one declaration, or Read with offset and limit",
    )

    private fun search(search: ShellIntent.Search): Verdict {
        if (search.filter == Filter.OTHER || search.patterns.any { it.isBlank() || '\n' in it }) return skip(Verdict.OTHER_FILES)
        val strong = search.filter == Filter.SOURCE || search.targets.any(SourceNames::isSource)
        if (search.targets.any(SourceNames::isOther)) return skip(Verdict.OTHER_FILES)
        if (!targetsAreIndexed(search)) return skip(Verdict.NOT_INDEXED)
        val pattern = search.patterns.singleOrNull()
        val shape = if (pattern != null) PatternShape.of(pattern, search.fixed) else PatternShape.Text(search.patterns.joinToString("|"), regex = true)
        return advise(
            anchor = search.targets.firstOrNull() ?: search.cwd,
            advice = when (shape) {
                is PatternShape.Declaration -> Advice("find", listOfNotNull("q" to shape.name, shape.kind?.let { "kind" to it }), "search:find", strong)
                is PatternShape.Name -> Advice("usages", listOf("name" to shape.name), "search:usages", strong, note = "`find q=\"${shape.name}\"` shows where it is declared")
                is PatternShape.Text -> Advice(
                    "grep", listOfNotNull("pattern" to shape.pattern, if (shape.regex) "regex" to true else null, if (search.ignoreCase) "ignoreCase" to true else null),
                    "search:grep", strong,
                )
            },
        )
    }

    // Every place searched is an indexed source file, a directory with indexed sources, or a glob of sources.
    private fun targetsAreIndexed(search: ShellIntent.Search): Boolean {
        if (search.targets.isEmpty()) return sources.hasSources(search.cwd)
        return search.targets.all { target ->
            val wild = target.substringAfterLast('/').any { it == '*' || it == '?' }
            if (SourceNames.isSource(target) && !wild) sources.file(target) != null else sources.hasSources(wildcardBase(target))
        }
    }

    private fun wildcardBase(path: String): String {
        val segments = path.split('/')
        val firstWild = segments.indexOfFirst { s -> s.any { it == '*' || it == '?' || it == '{' || it == '[' } }
        return (if (firstWild >= 0) segments.take(firstWild) else segments).joinToString("/").ifEmpty { "/" }
    }

    private fun fileList(list: ShellIntent.FileList): Verdict {
        val names = list.names.filter { SourceNames.mentionsSource(it) }
        if (names.isEmpty() || names.size != list.names.size) return skip(Verdict.OTHER_FILES)
        val roots = list.roots.ifEmpty { listOf(list.cwd) }
        if (!roots.all { sources.hasSources(wildcardBase(it)) }) return skip(Verdict.NOT_INDEXED)
        val stems = names.map { it.substringAfterLast('/').replace(Regex("""(?i)\.(kt|kts|java)$|\.\{[a-z,]+}$"""), "") }.distinct()
        val stem = stems.singleOrNull()?.takeIf { it.isNotEmpty() && it.any { c -> c != '*' && c != '?' } }
        val advice = if (stem != null) Advice("find", listOf("q" to stem), "files:find", strong = true) else Advice("outline", emptyList(), "files:outline", strong = true)
        return advise(advice, roots.first())
    }
}
