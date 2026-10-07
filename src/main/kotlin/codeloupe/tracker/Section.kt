package codeloupe.tracker

/** A part of an issue description under one markdown heading; the text before the first heading has an empty title. */
data class Section(val title: String, val text: String) {
    val lines: Int get() = text.lines().count { it.isNotBlank() }

    companion object {
        private val HEADING = Regex("^#{1,3} +(.+?)\\s*#*\\s*$")

        fun parse(description: String): List<Section> {
            val sections = ArrayList<Section>()
            var title = ""
            val body = StringBuilder()
            var fenced = false
            fun flush() {
                val text = body.toString().trim('\n', '\r', ' ')
                if (title.isNotEmpty() || text.isNotBlank()) sections += Section(title, text)
                body.clear()
            }
            for (line in description.lines()) {
                if (line.trimStart().startsWith("```")) fenced = !fenced
                val heading = if (fenced) null else HEADING.find(line)
                if (heading != null) {
                    flush()
                    title = heading.groupValues[1]
                } else {
                    body.append(line).append('\n')
                }
            }
            flush()
            return sections
        }
    }
}
