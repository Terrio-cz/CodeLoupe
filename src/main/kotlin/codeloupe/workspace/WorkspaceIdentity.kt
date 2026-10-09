package codeloupe.workspace

import codeloupe.docker.Ownership
import codeloupe.platform.PathCase
import java.nio.file.Path

/** Which workspace of the registry a directory is in. */
object WorkspaceIdentity {
    /** The ownership labels for work done in [dir]: the deepest workspace of [list] that contains it. */
    fun pick(list: WorkspaceList, dir: Path): Ownership {
        val here = key(dir.toString())
        val candidates = list.repos.flatMap { repo -> repo.workspaces.map { repo to it } }
            .filter { (_, w) -> key(w.path).let { here == it || here.startsWith("$it/") } }
        val (repo, workspace) = candidates.maxByOrNull { (_, w) -> w.path.length }
            ?: throw IllegalArgumentException("$dir is not in a workspace of a known repository; list the repository under workspaces.repos in config.json")
        return Ownership(repo.name, workspace.name, workspace.taskId.orEmpty())
    }

    private fun key(path: String): String {
        val normal = Path.of(path).toAbsolutePath().normalize().toString().replace('\\', '/').trimEnd('/')
        return PathCase.fold(normal)
    }
}
