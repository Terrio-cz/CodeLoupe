package codeloupe.brain

import codeloupe.config.WorkspacesConfig
import codeloupe.platform.PathCase
import codeloupe.platform.Sha1
import java.nio.file.Files
import java.nio.file.Path

/** A workspace directory that has a brain. [id] is derived from the path, so the UI API never takes a path from a request. */
class BrainRoot(val id: String, val path: Path) {
    val name: String get() = path.fileName?.toString() ?: path.toString()

    companion object {
        /**
         * The workspaces of CodeLoupe's registry that have `brain/brain.mjs`: the configured `workspaces.brains` and the
         * repositories of `workspaces.repos`, each once, in that order.
         */
        fun all(config: WorkspacesConfig): List<BrainRoot> =
            (config.brains + config.repos.map { it.path }).mapNotNull(::of).distinctBy { it.id }
                .filter { Files.isRegularFile(it.path.resolve(BrainCli.SCRIPT)) }

        fun find(config: WorkspacesConfig, id: String): BrainRoot? = all(config).firstOrNull { it.id == id }

        /** The registered workspace at [path] (a workspace directory, or any directory inside it), or null. */
        fun at(config: WorkspacesConfig, path: String): BrainRoot? {
            val wanted = of(path) ?: return null
            all(config).firstOrNull { it.id == wanted.id }?.let { return it }
            return all(config).firstOrNull { wanted.path.startsWith(it.path) }
        }

        private fun of(text: String): BrainRoot? = runCatching {
            val path = Path.of(text).toAbsolutePath().normalize()
            BrainRoot(Sha1.hex(PathCase.fold(path.toString().replace('\\', '/').trimEnd('/'))).take(12), path)
        }.getOrNull()
    }
}
