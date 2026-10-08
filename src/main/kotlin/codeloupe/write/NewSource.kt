package codeloupe.write

import codeloupe.lang.FileFacts

/**
 * The text of a file to be created: the code with the line end its neighbours use and one at the end, checked against where
 * it is going - the package statement has to be the folder's, a public Java type has to carry the file's name.
 */
internal object NewSource {
    private val SOURCE_ROOT = Regex("""(?:^|/)src/[^/]+/(?:kotlin|java)/(.+)/[^/]+$""")

    /** [path] is relative to the root, with `/`; [eol] is the line end to write; [facts] are those of [code]. */
    fun text(path: String, code: String, eol: String, facts: FileFacts): String {
        if (facts.errors > 0) throw WriteRefused("the code has ${facts.errors} syntax error(s); nothing was created")
        val folder = SOURCE_ROOT.find(path)?.groupValues?.get(1)?.replace('/', '.')
        if (folder != null && facts.packageName != folder) {
            throw WriteRefused("the package of $path is $folder, the code says ${facts.packageName.ifEmpty { "nothing" }}")
        }
        val name = path.substringAfterLast('/').substringBeforeLast('.')
        if (path.endsWith(".java", ignoreCase = true)) {
            facts.decls.firstOrNull { it.parent < 0 && "public" in it.modifiers && it.name != name }
                ?.let { throw WriteRefused("a public type of a Java file is named like the file: ${it.name} in $name.java") }
        }
        val lines = Reindent.trimmed(code)
        if (lines.isEmpty()) throw WriteRefused("the code is empty")
        return lines.joinToString(eol, postfix = eol)
    }
}
