package codeloupe.tracker.read

/**
 * What of an issue a read asks for: the brief, everything, or named sections — description headings matched by
 * prefix, or `criteria`, `fields`, `links`, `comments`, `attachments`, `history`.
 */
data class Parts(val full: Boolean, val sections: Set<String>) {
    val brief: Boolean get() = !full && sections.isEmpty()

    /** True when a reader who saw [seen] has seen everything these parts show. */
    fun within(seen: Parts): Boolean = when {
        seen.full -> true
        full -> false
        brief -> seen.brief
        else -> sections.all { it in seen.sections }
    }

    fun plus(other: Parts): Parts = Parts(full || other.full, sections + other.sections)

    /** Whether a description section or pseudo-section [name] is shown. */
    fun shows(name: String): Boolean = full || sections.any { matches(it, name) }

    companion object {
        val PSEUDO = listOf("criteria", "fields", "links", "comments", "attachments", "history")

        fun of(view: String?, sections: List<String>): Parts =
            Parts(view == "full", if (view == "full") emptySet() else sections.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet())

        fun matches(requested: String, title: String): Boolean = title.lowercase().startsWith(requested.lowercase())
    }
}
