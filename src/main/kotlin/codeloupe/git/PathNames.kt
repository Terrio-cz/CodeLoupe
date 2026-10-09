package codeloupe.git

/**
 * A path with a control character in it (a newline, an escape, a NUL) is a file name nobody writes by hand. It would end up inside the text
 * handed to an agent (the session-start orientation, tool results), where a newline is a way to add a line of instructions to what looks
 * like a listing. Such paths are left out of the index and of the change lists, so they appear nowhere.
 */
object PathNames {
    fun plain(path: String): Boolean = path.none { it.isISOControl() }
}
