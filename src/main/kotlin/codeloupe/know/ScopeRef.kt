package codeloupe.know

/** A scope with its target: `repo`, `area:docker`, `file:src/Foo.kt`, `symbol:Foo.bar`, `task:TER-12`. */
data class ScopeRef(val kind: FactScope, val target: String = "") {
    override fun toString() = if (target.isEmpty()) kind.word else "${kind.word}:$target"

    companion object {
        /** A fact's scope: everything but `repo` needs a target. */
        fun parse(text: String): ScopeRef = parse(text, filter = false)

        /** A filter: a scope without a target (`area`) stands for all its targets. */
        fun parseAll(text: String?): List<ScopeRef> = text.orEmpty().split(',').filter { it.isNotBlank() }.map { parse(it, filter = true) }

        private fun parse(text: String, filter: Boolean): ScopeRef {
            val kind = text.substringBefore(':').trim().lowercase()
            val scope = FactScope.entries.firstOrNull { it.word == kind }
                ?: throw IllegalArgumentException("scope must start with one of ${FactScope.entries.joinToString("/") { it.word }} (area:docker, file:src/Foo.kt): $text")
            val target = if (':' in text) text.substringAfter(':').trim() else ""
            if (!filter && scope != FactScope.REPO && target.isEmpty()) throw IllegalArgumentException("scope ${scope.word} needs a target, e.g. ${scope.word}:<name>")
            return ScopeRef(scope, target)
        }
    }
}
