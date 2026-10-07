package codeloupe.tracker

/** One `- [ ]` / `- [x]` line of an issue description. */
data class Criterion(val done: Boolean, val text: String) {
    companion object {
        private val LINE = Regex("^\\s*[-*+] \\[([ xX])] (.+)$")

        fun parse(description: String): List<Criterion> = description.lineSequence()
            .mapNotNull { LINE.find(it) }
            .map { Criterion(it.groupValues[1] != " ", it.groupValues[2].trim()) }
            .toList()
    }
}
