package codeloupe.hooks

/** An index of a repository at [root] made of [files] (relative path to line count), for the decision tests. */
class FakeSources(private val root: String, private val files: Map<String, Int>, private val failing: Boolean = false) : SourceFiles {
    override fun file(path: String): SourceFile? {
        check(!failing) { "index unavailable" }
        val relative = path.removePrefix("$root/").takeIf { it != path } ?: return null
        return files[relative]?.let { SourceFile(path, relative, it) }
    }

    override fun hasSources(dir: String): Boolean {
        check(!failing) { "index unavailable" }
        val relative = dir.removePrefix(root).trim('/').takeIf { dir == root || dir.startsWith("$root/") } ?: return false
        return relative.isEmpty() || files.keys.any { it.startsWith("$relative/") }
    }
}
