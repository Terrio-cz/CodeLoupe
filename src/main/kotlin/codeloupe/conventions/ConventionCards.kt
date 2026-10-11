package codeloupe.conventions

import java.nio.file.Path

/**
 * The entry point for a pack or a command: cards for modules of a repository root, or for the modules that a set of
 * repository-relative paths (a task's touched files) belong to. Nothing is cached or written; a card is rebuilt from the
 * sources on the disk, so it follows a worktree.
 */
object ConventionCards {
    fun card(module: Module): String = ConventionCard.render(module, ModuleReader.read(module))

    /** The modules that own [paths] (the nearest enclosing module of each), in order of first appearance, at most [limit]. */
    fun owning(root: Path, paths: List<String>, limit: Int = 2): List<Module> {
        val modules = ModuleFinder.find(root)
        return paths.mapNotNull { path -> modules.filter { path.replace('\\', '/').startsWith(prefix(root, it)) }.maxByOrNull { it.dir.nameCount } }
            .distinct().take(limit)
    }

    /** Modules by main source lines, largest first. */
    fun largest(root: Path): List<Pair<Module, Int>> = ModuleFinder.find(root).map { it to ModuleReader.mainLines(it) }.filter { it.second > 0 }
        .sortedWith(compareByDescending<Pair<Module, Int>> { it.second }.thenBy { it.first.name })

    private fun prefix(root: Path, module: Module): String {
        val relative = root.relativize(module.dir).joinToString("/") { it.toString() }
        return if (relative.isEmpty()) "" else "$relative/"
    }
}
