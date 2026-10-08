package codeloupe.query

/**
 * The directory every path of a list shares, named once so the lines carry only what differs. Nothing is factored out
 * of a single path or of a prefix too short to pay for the note.
 */
internal object CommonDir {
    private const val MIN_LENGTH = 12

    /** The shared directory with its trailing `/`, or "" when there is none worth naming. */
    fun of(paths: Collection<String>): String {
        if (paths.size < 2) return ""
        val dirs = paths.map { it.substringBeforeLast('/', "") }.distinct()
        var prefix = dirs.first().split('/')
        for (dir in dirs.drop(1)) {
            val parts = dir.split('/')
            prefix = prefix.zip(parts).takeWhile { (a, b) -> a == b }.map { it.first }
        }
        val common = prefix.joinToString("/")
        return if (common.length < MIN_LENGTH) "" else "$common/"
    }

    /** `name (under dir/):` when [dir] is shared, else `name:`. */
    fun heading(name: String, dir: String): String = if (dir.isEmpty()) "$name:" else "$name (under $dir):"
}
