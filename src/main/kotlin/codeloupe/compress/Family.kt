package codeloupe.compress

/** One kind of command output and how to shorten it without hiding what an agent must act on. */
interface Family {
    /** [line] is the command as one text (`git -C x status -s`). */
    fun matches(line: String): Boolean

    /** The shortened [text] of the command's output (ANSI codes already removed); [cwd] is cut off paths. */
    fun compress(text: String, cwd: String): String
}
