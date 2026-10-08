package codeloupe.hooks

/** What the hooks may ask the index: whether a file or a directory is source CodeLoupe answers for. */
interface SourceFiles {
    /** The indexed source file at the absolute [path], with its line count; null when the daemon does not know the repository or the file. */
    fun file(path: String): SourceFile?

    /** True when the absolute [dir] lies in a repository the daemon has an index for and holds indexed source files. */
    fun hasSources(dir: String): Boolean
}
