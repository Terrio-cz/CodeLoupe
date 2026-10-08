package codeloupe.query

import codeloupe.query.usages.HitLines
import codeloupe.query.usages.ShortSignature

/**
 * `grep`: text search over the indexed source of a worktree (base index plus overlay, so uncommitted edits count),
 * every hit labelled with the declaration that encloses it and grouped under it. For what the declaration index does
 * not hold: string literals, SQL, annotation arguments, config keys written in code.
 */
object GrepQuery {
    data class Args(
        val pattern: String?,
        val regex: Boolean = false,
        val ignoreCase: Boolean = false,
        val module: String? = null,
        val test: Boolean? = null,
        val limit: Int = 40,
    )

    private class Hit(val line: Int, val text: String)

    // Hits kept for ordering; the ones past this are counted only, so a pattern matching everything stays cheap.
    private const val KEPT = 5000

    fun run(view: View, args: Args): String {
        val pattern = args.pattern.orEmpty()
        if (pattern.isEmpty()) return "grep needs a pattern"
        val matcher = try {
            Matcher.of(pattern, args)
        } catch (e: java.util.regex.PatternSyntaxException) {
            return "invalid regex: ${e.description}"
        }
        val byPath = HashMap<String, List<Hit>>()
        var total = 0
        var kept = 0
        val prefilter = pattern.takeUnless { args.regex || args.ignoreCase }
        view.scanContent(prefilter, args.module, args.test) { path, content ->
            val hits = ArrayList<Hit>()
            content.split('\n').forEachIndexed { i, raw ->
                val line = raw.removeSuffix("\r")
                if (!matcher.matches(line)) return@forEachIndexed
                total++
                if (kept < KEPT) {
                    kept++
                    hits += Hit(i + 1, line)
                }
            }
            if (hits.isNotEmpty()) byPath[path] = hits
        }
        if (total == 0) return "no match for \"$pattern\" in the indexed source (Kotlin and Java files)"
        val paths = byPath.keys.sortedWith(PathOrder)
        val out = StringBuilder("grep \"$pattern\": $total hit${if (total == 1) "" else "s"} in ${paths.size} file${if (paths.size == 1) "" else "s"}")
        var shown = 0
        for (path in paths) {
            if (shown >= args.limit) break
            val decls = view.decls("f.path = :path AND d.local = 0", mapOf("path" to path))
            out.append('\n').append(path)
            var owner: DeclRow? = null
            var first = true
            for (hit in byPath.getValue(path)) {
                if (shown >= args.limit) break
                val enclosing = enclosing(decls, hit.line)
                if (first || enclosing != owner) out.append("\n  ").append(enclosing?.let(ShortSignature::of) ?: "(file level)")
                first = false
                owner = enclosing
                out.append("\n    ${hit.line} ${HitLines.snippet(hit.text, matcher.column(hit.text))}")
                shown++
            }
        }
        val rest = total - shown
        if (rest > 0) out.append("\n… +$rest more hits (narrow with module/test, a longer pattern, or raise limit)")
        return out.toString()
    }

    /** The innermost non-local declaration whose lines hold [line]. */
    private fun enclosing(decls: List<DeclRow>, line: Int): DeclRow? =
        decls.filter { line in it.startLine..it.endLine }.minByOrNull { it.endLine - it.startLine }

    private class Matcher private constructor(private val regex: Regex?, private val literal: String, private val ignoreCase: Boolean) {
        fun matches(line: String) = if (regex != null) regex.containsMatchIn(line) else line.contains(literal, ignoreCase)

        /** 1-based column of the first match, for windowing a long line around it. */
        fun column(line: String): Int {
            val at = if (regex != null) regex.find(line)?.range?.first else line.indexOf(literal, ignoreCase = ignoreCase)
            return 1 + (at ?: 0).coerceAtLeast(0)
        }

        companion object {
            fun of(pattern: String, args: Args) =
                if (args.regex) Matcher(Regex(pattern, if (args.ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()), pattern, args.ignoreCase)
                else Matcher(null, pattern, args.ignoreCase)
        }
    }
}
