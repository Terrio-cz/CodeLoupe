package codeloupe.platform

/**
 * Whether paths differ by case. Windows (NTFS) and macOS (APFS and HFS+ as formatted by default) keep the case a name was
 * created with but find it by any case, so two spellings of one path must give one key there; Linux file systems tell them
 * apart. A case-sensitive macOS volume would be keyed as one that is not: two paths that differ by case only share a key there.
 */
object PathCase {
    val insensitive: Boolean = isInsensitive(System.getProperty("os.name"))

    internal fun isInsensitive(os: String): Boolean = os.lowercase().let { it.startsWith("windows") || it.startsWith("mac") }

    /** [path] in the form paths are compared in on this OS. */
    fun fold(path: String, insensitive: Boolean = PathCase.insensitive): String = if (insensitive) path.lowercase() else path
}
