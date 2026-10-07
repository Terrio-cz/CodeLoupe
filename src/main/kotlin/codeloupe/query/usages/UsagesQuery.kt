package codeloupe.query.usages

import codeloupe.query.Members
import codeloupe.query.Resolver
import codeloupe.query.View

/** `usages`: references to a declaration grouped by file and enclosing declaration, labelled exact or candidate. */
object UsagesQuery {
    data class Args(val name: String?, val limit: Int = 40, val all: Boolean = false)

    fun run(view: View, args: Args): String {
        val name = args.name.orEmpty()
        val targets = Resolver.resolve(view, name)
        if (targets.isEmpty()) return "no declaration \"$name\"" + Members.suggest(view, name)
        TargetLines.ambiguity(name, targets)?.let { return it }
        val finder = UsageFinder(view)
        val lines = HitLines.lines(finder.usages(targets))
        val counts = Label.entries.associateWith { label -> lines.count { it.label == label } }
        val shown = if (args.all) lines else lines.filter { it.label != Label.OTHER }
        val header = TargetLines.header("usages", targets) + "\n${counts[Label.EXACT]} exact, ${counts[Label.CANDIDATE]} candidate" +
            if (args.all) ", ${counts[Label.OTHER]} other" else ""
        val body = HitLines.render(shown.take(args.limit), finder.cache)
        val rest = shown.size - minOf(shown.size, args.limit)
        val footer = buildList {
            if (rest > 0) add("… +$rest more in ${shown.drop(args.limit).map { it.ref.path }.distinct().size} files (raise limit)")
            val other = counts.getValue(Label.OTHER)
            if (!args.all && other > 0) add("$other more lines with the name resolve to other declarations (all=true lists them)")
        }
        return listOf(header, body).filter { it.isNotEmpty() }.joinToString("\n") + footer.joinToString("") { "\n$it" }
    }
}
