package codeloupe.taskcode

import codeloupe.index.ModulePath
import codeloupe.query.DeclRow
import codeloupe.query.PathOrder
import codeloupe.query.Resolver
import codeloupe.query.View
import java.nio.file.Files
import java.nio.file.Path

/**
 * Resolves an issue's mentions against the code index into a predicted touch set: paths and symbols the index has
 * (`=`), words and strings found once (`~`), several candidates or a module (`?`), and paths it lacks (`+`, new).
 * Words that match nothing are dropped: prose is full of them; code spans that match nothing are reported, and
 * names that match declarations all over the repository are listed as ambiguous rather than guessed at.
 */
class TouchPrediction(private val view: View, private val worktree: Path) {
    class Result(val predictions: List<Prediction>, val unresolved: List<Mention>, val ambiguous: List<Mention>)

    fun of(mentions: List<Mention>): Result {
        val found = LinkedHashMap<String, Prediction>()
        val unresolved = ArrayList<Mention>()
        val ambiguous = ArrayList<Mention>()
        val modules by lazy { view.modules().filter { it.isNotEmpty() } }
        fun add(p: Prediction) {
            val old = found[p.target]
            found[p.target] = if (old == null) p else {
                val best = if (Prediction.ORDER.indexOf(p.mark) < Prediction.ORDER.indexOf(old.mark)) p else old
                best.copy(evidence = (old.evidence + p.evidence).distinct().take(MAX_EVIDENCE))
            }
        }
        for (m in mentions) {
            when (val outcome = resolve(m, modules)) {
                is Outcome.Found -> outcome.predictions.forEach(::add)
                Outcome.Unresolved -> unresolved += m
                Outcome.Ambiguous -> ambiguous += m
                Outcome.Nothing -> Unit
            }
        }
        val ordered = found.values.sortedWith(compareBy<Prediction> { Prediction.ORDER.indexOf(it.mark) }.thenComparing({ it.path ?: it.target }, PathOrder))
        return Result(ordered, unresolved, ambiguous)
    }

    /**
     * The best of [mentions] that lands on [target], a path: what `code_tasks` asks of every open task. Literals are
     * looked for in the target's own text, never across the repository, so a pass over hundreds of tasks stays cheap.
     */
    fun onPath(mentions: List<Mention>, target: String): Prediction? {
        var best: Prediction? = null
        fun offer(p: Prediction) {
            if (best == null || Prediction.ORDER.indexOf(p.mark) < Prediction.ORDER.indexOf(best!!.mark)) best = p
        }
        val content by lazy { view.file(target)?.content ?: onDiskText(target) }
        val module = ModulePath.of(target).module
        for (m in mentions) {
            when (m.kind) {
                Mention.Kind.PATH -> {
                    val (suffix, _) = Mentions.pathSuffix(m.text)
                    if (target == suffix) offer(Prediction(Prediction.SURE, target, target, "", listOf(m.evidence)))
                    else if (target.endsWith("/$suffix")) offer(Prediction(if (view.filesBySuffix("/$suffix").size <= 1) Prediction.SURE else Prediction.GUESS, target, target, "", listOf(m.evidence)))
                }
                // A name declared all over (`source`, `id`) is as ambiguous here as in a full prediction: it points at nothing.
                Mention.Kind.SYMBOL -> onTarget(Resolver.resolve(view, m.text).filter { !it.local }, target, m, Prediction.SURE)?.let(::offer)
                Mention.Kind.WORD -> onTarget(view.decls("d.name = :name AND d.local = 0", mapOf("name" to m.text)), target, m, Prediction.LIKELY)?.let(::offer)
                Mention.Kind.LITERAL -> needle(m.text)?.let { n -> if (content?.contains(n) == true) offer(Prediction(Prediction.GUESS, target, target, "holds `$n`", listOf(m.evidence))) }
                Mention.Kind.MODULE -> if (module.isNotEmpty() && (module == m.text || module.endsWith("/${m.text}"))) offer(Prediction(Prediction.GUESS, "module $module", null, "", listOf(m.evidence)))
            }
        }
        return best
    }

    /** The declaration of [rows] in [target], marked [sure] when the name means one file, a guess when it means a few, nothing when many. */
    private fun onTarget(rows: List<DeclRow>, target: String, m: Mention, sure: Char): Prediction? {
        val files = rows.map { it.path }.distinct()
        if (files.size > MAX_WORD_FILES) return null
        val hit = rows.firstOrNull { it.path == target } ?: return null
        return declPrediction(if (files.size == 1) sure else Prediction.GUESS, hit, m)
    }

    private fun resolve(m: Mention, modules: List<String>): Outcome = when (m.kind) {
        Mention.Kind.PATH -> path(m)
        Mention.Kind.SYMBOL -> symbol(m)
        Mention.Kind.WORD -> word(m)
        Mention.Kind.LITERAL -> literal(m)
        Mention.Kind.MODULE -> module(m, modules)
    }

    private fun path(m: Mention): Outcome {
        val (suffix, _) = Mentions.pathSuffix(m.text)
        val line = Mentions.line(m.text)
        val exact = Resolver.resolvePath(view, suffix)
        if (exact != null) {
            val decl = line?.let { Resolver.resolve(view, "$exact:$it").firstOrNull() }
            return Outcome.Found(listOf(if (decl != null) declPrediction(Prediction.SURE, decl, m) else Prediction(Prediction.SURE, exact, exact, "", listOf(m.evidence))))
        }
        val candidates = view.filesBySuffix(if (suffix.startsWith("/")) suffix else "/$suffix")
        if (candidates.isEmpty()) {
            // The index holds source files only: a docs or build file is looked up on disk. A whole path neither has is probably a file to come.
            if (onDisk(suffix)) return Outcome.Found(listOf(Prediction(Prediction.SURE, suffix, suffix, "(not indexed)", listOf(m.evidence))))
            return if ('/' in suffix) Outcome.Found(listOf(Prediction(Prediction.NEW, suffix, suffix, "not in the repository: new?", listOf(m.evidence)))) else Outcome.Unresolved
        }
        return Outcome.Found(listOf(Prediction(Prediction.GUESS, suffix, null, "${candidates.size} files: ${few(candidates)}", listOf(m.evidence))))
    }

    private fun symbol(m: Mention): Outcome {
        val rows = Resolver.resolve(view, m.text).filter { !it.local }
        if (rows.isEmpty()) {
            // `MailSender.kt` in a code span is a path; `Foo` may also be a file name.
            val asPath = view.filesBySuffix("/${m.text.substringBefore('(')}.kt") + view.filesBySuffix("/${m.text.substringBefore('(')}.java")
            return asPath.singleOrNull()?.let { Outcome.Found(listOf(Prediction(Prediction.LIKELY, it, it, "file named like `${m.text}`", listOf(m.evidence)))) } ?: Outcome.Unresolved
        }
        return candidates(rows, m, sure = Prediction.SURE)
    }

    private fun word(m: Mention): Outcome {
        val rows = view.decls("d.name = :name AND d.local = 0", mapOf("name" to m.text))
        if (rows.isEmpty()) return Outcome.Nothing
        // A word that names declarations in many files says little about which one the task means.
        if (rows.map { it.path }.distinct().size > MAX_WORD_FILES) return Outcome.Nothing
        return candidates(rows, m, sure = Prediction.LIKELY)
    }

    /** One declaration, or several in one file, is a target; a few files are a guess; many are an ambiguous name. */
    private fun candidates(rows: List<DeclRow>, m: Mention, sure: Char): Outcome {
        val files = rows.map { it.path }.distinct()
        if (rows.size == 1) return Outcome.Found(listOf(declPrediction(sure, rows.single(), m)))
        if (files.size == 1) {
            val path = files.single()
            return Outcome.Found(listOf(Prediction(sure, path, path, "${rows.size} declarations named ${m.text}", listOf(m.evidence))))
        }
        if (files.size > MAX_WORD_FILES) return Outcome.Ambiguous
        return Outcome.Found(listOf(Prediction(Prediction.GUESS, m.text, null, "${rows.size} declarations in ${files.size} files: ${few(files)}", listOf(m.evidence))))
    }

    private fun literal(m: Mention): Outcome {
        val needle = needle(m.text) ?: return Outcome.Nothing
        val files = view.filesContaining(needle, MAX_LITERAL_FILES + 1)
        return when {
            files.isEmpty() -> Outcome.Unresolved
            files.size == 1 -> Outcome.Found(listOf(Prediction(Prediction.LIKELY, "${files.single()}:${lineOf(files.single(), needle)}", files.single(), "holds `$needle`", listOf(m.evidence))))
            files.size > MAX_LITERAL_FILES -> Outcome.Ambiguous
            else -> Outcome.Found(listOf(Prediction(Prediction.GUESS, m.text, null, "in ${files.size} files: ${few(files)}", listOf(m.evidence))))
        }
    }

    // A module name the index lacks (`origin/master`) is prose, not a miss worth reporting.
    private fun module(m: Mention, modules: List<String>): Outcome {
        val path = m.text.replace('\\', '/').trimEnd('/')
        val hits = modules.filter { it == path || it.endsWith("/$path") }
        if (hits.isEmpty()) return Outcome.Nothing
        return Outcome.Found(hits.map { Prediction(Prediction.GUESS, "module $it", null, "", listOf(m.evidence)) })
    }

    /** The longest fixed piece of a route (`/v1/sources/{id}` -> `/v1/sources/`), the method dropped; null when too short to mean anything. */
    private fun needle(text: String): String? {
        val route = text.substringAfter(' ', text).trim()
        val fixed = route.split(Regex("\\{[^}]*}|:[A-Za-z_]+|\\*")).maxByOrNull { it.length }.orEmpty()
        return fixed.takeIf { it.length >= MIN_NEEDLE }
    }

    private fun onDisk(path: String): Boolean = !path.contains("..") && runCatching { Files.isRegularFile(worktree.resolve(path)) }.getOrDefault(false)

    private fun onDiskText(path: String): String? = if (onDisk(path)) runCatching { Files.readString(worktree.resolve(path)) }.getOrNull() else null

    private fun lineOf(path: String, needle: String): Int =
        view.file(path)?.content?.lineSequence()?.indexOfFirst { needle in it }?.takeIf { it >= 0 }?.plus(1) ?: 1

    private fun declPrediction(mark: Char, d: DeclRow, m: Mention) =
        Prediction(mark, "${d.path}:${d.startLine}-${d.endLine}", d.path, (if (d.container.isNotEmpty()) "[${d.container}] " else "") + d.sig, listOf(m.evidence))

    private fun few(paths: List<String>) = paths.take(3).joinToString(", ") { it.substringAfterLast('/') } + if (paths.size > 3) ", …" else ""

    private sealed interface Outcome {
        class Found(val predictions: List<Prediction>) : Outcome
        data object Unresolved : Outcome
        data object Ambiguous : Outcome
        data object Nothing : Outcome
    }

    private companion object {
        const val MAX_EVIDENCE = 2
        const val MAX_WORD_FILES = 6
        const val MAX_LITERAL_FILES = 5
        const val MIN_NEEDLE = 4
    }
}
