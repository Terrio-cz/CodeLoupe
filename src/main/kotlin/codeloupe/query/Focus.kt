package codeloupe.query

/** The files a map is centred on, from file paths and symbol names; [missing] are the entries that named nothing. */
internal class Focus(val paths: Set<String>, val missing: List<String>) {
    companion object {
        private val FILE = Regex("(?i)(/|\\.(kt|kts|java)$)")

        fun resolve(view: View, entries: List<String>): Focus {
            val paths = LinkedHashSet<String>()
            val missing = ArrayList<String>()
            for (entry in entries.map { it.trim() }.filter { it.isNotEmpty() }) {
                val found = if (FILE.containsMatchIn(entry)) listOfNotNull(Resolver.resolvePath(view, entry)) else Resolver.resolve(view, entry).map { it.path }
                if (found.isEmpty()) missing += entry else paths += found
            }
            return Focus(paths, missing)
        }
    }
}
