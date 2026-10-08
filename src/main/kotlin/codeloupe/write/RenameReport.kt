package codeloupe.write

/** The answer to a rename: what was (or would be) changed, then what is left to the caller. */
internal object RenameReport {
    private const val LISTED = 15

    fun text(plan: RenamePlan, applied: Boolean): String {
        val files = (plan.sites.map { it.path } + plan.family.map { it.path } + plan.importPaths.keys).distinct()
        val imports = plan.importPaths.values.sumOf { it.size }
        val head = "${if (applied) "renamed" else "would rename"} ${plan.targets.first().fqn} -> ${plan.newName}: " +
            "${plan.family.size} declaration(s), ${plan.sites.size} usage(s), $imports import(s) in ${files.size} file(s)" +
            (plan.move?.let { ", ${it.first.substringAfterLast('/')} -> ${it.second.substringAfterLast('/')}" } ?: "") + if (applied) "" else " (dry run: nothing written)"
        val lines = ArrayList<String>()
        lines += head
        if (plan.family.size > plan.targets.size) {
            lines += "with the members it overrides or is overridden by: " + plan.family.drop(plan.targets.size).take(LISTED).joinToString { "${it.container}.${it.name}".trimStart('.') }
        }
        if (plan.candidates.isNotEmpty()) {
            lines += "left to you (${plan.candidates.size}; the index is not sure they are uses):"
            plan.candidates.take(LISTED).forEach { lines += "  ${it.path}:${it.line}  ${it.note}" }
            if (plan.candidates.size > LISTED) lines += "  ... +${plan.candidates.size - LISTED} more"
        }
        plan.warnings.forEach { lines += "note: $it" }
        lines += "strings, comments and generated code are not changed"
        return lines.joinToString("\n")
    }
}
