package codeloupe.overlay

/**
 * Which other overlay stores are worth opening before an update parses its files. Opening a store read-only costs several
 * milliseconds (on Windows 5-9 ms; 8 stores added 60 ms to the refresh of one edited file), a parse of one file about 5.
 * A store can hold a file only when its worktree's overlay lists the path, which the daemon knows for every worktree it has
 * checked since it started. The store of a worktree it has not checked yet is left out: opening it to look would cost more
 * than the parse it might save (the base sync of a landing still tries every store).
 */
internal object OverlaySources {
    /** [others]: the store files of the repository except the one being written. [known]: the paths each known store holds. */
    fun relevant(others: List<String>, known: Map<String, Set<String>>, paths: Set<String>): List<String> =
        others.filter { store -> known[store]?.let { held -> paths.any { it in held } } == true }

    /** The stores of [states] by file name, with the paths their overlays hold. */
    fun known(states: Collection<OverlayState>): Map<String, Set<String>> =
        states.associate { it.file.toString() to it.entries.keys }
}
