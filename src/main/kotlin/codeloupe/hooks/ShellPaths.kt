package codeloupe.hooks

/**
 * Turns the path words of a shell command into absolute paths with forward slashes, by text alone: Git Bash spells a
 * Windows drive `/c/Users/x`, PowerShell `C:\Users\x`, and neither exists on every machine the hook is tested on.
 */
class ShellPaths(private val home: String, private val windows: Boolean) {
    /** The absolute form of [word] seen from [cwd]; null for a word that is not a plain path (a variable, a substitution, empty); wildcards stay in the result. */
    fun resolve(cwd: String, word: String): String? {
        if (word.isEmpty() || word.any { it == '$' || it == '`' }) return null
        val text = word.replace('\\', '/')
        val absolute = when {
            text == "~" -> home
            text.startsWith("~/") -> "$home/${text.substring(2)}"
            else -> drive(text) ?: text
        }
        // Claude Code sends the working directory of a Windows session with backslashes: `..` must not take the whole of it for one name.
        val base = if (isAbsolute(absolute)) absolute else "${cwd.replace('\\', '/').trimEnd('/')}/$absolute"
        return normalize(base)
    }

    fun isAbsolute(path: String): Boolean = path.startsWith("/") || DRIVE.containsMatchIn(path)

    // `/c/Users/x` and `/mnt/c/Users/x` name a drive only on Windows; elsewhere they are ordinary directories.
    private fun drive(text: String): String? {
        if (!windows) return null
        val match = GIT_BASH.find(text) ?: return null
        return "${match.groupValues[1].uppercase()}:/${match.groupValues[2]}"
    }

    private fun normalize(path: String): String {
        val prefix = DRIVE.find(path)?.value ?: if (path.startsWith("/")) "/" else ""
        val parts = ArrayList<String>()
        for (part in path.removePrefix(prefix).split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts += part
            }
        }
        val drive = if (prefix.length == 2) "${prefix[0].uppercase()}:/" else prefix
        return drive + parts.joinToString("/")
    }

    private companion object {
        val DRIVE = Regex("^[A-Za-z]:/?")
        val GIT_BASH = Regex("^/(?:mnt/)?([A-Za-z])(?:/(.*))?$")
    }
}
