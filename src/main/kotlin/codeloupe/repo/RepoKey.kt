package codeloupe.repo

import codeloupe.JsonFormat
import codeloupe.platform.PathCase
import codeloupe.platform.Sha1
import java.nio.file.Files
import java.nio.file.Path

/**
 * The id of a repository under `<home>/repos`: 12 hex of the SHA-1 of its common dir, spelled the way the file system compares paths
 * ([PathCase]). Until CL-165 every OS folded the case, so on Linux `/src/Foo` and `/src/foo` shared one index directory and overwrote each
 * other's record and base. An index made that way is still used by the repository it names (its `repo.json` holds the common dir), so
 * nothing is built again; a directory that names another spelling is left to that one.
 */
internal object RepoKey {
    fun id(commonDir: String, insensitive: Boolean = PathCase.insensitive): String = Sha1.hex(PathCase.fold(commonDir, insensitive)).take(ID_LENGTH)

    fun resolve(repos: Path, commonDir: String, insensitive: Boolean = PathCase.insensitive): String {
        if (insensitive) return id(commonDir, insensitive = true)
        val exact = id(commonDir, insensitive = false)
        val folded = id(commonDir.lowercase(), insensitive = false)
        for (candidate in listOf(exact, folded).distinct()) {
            if (recorded(repos, candidate) == commonDir) return candidate
        }
        if (recorded(repos, exact) == null) return exact
        // The exact spelling's directory belongs to the other one: an old index of a differently-cased twin.
        return Sha1.hex("exact:$commonDir").take(ID_LENGTH)
    }

    private fun recorded(repos: Path, id: String): String? =
        runCatching { JsonFormat.json.decodeFromString(RepoRecord.serializer(), Files.readString(repos.resolve(id).resolve("repo.json"))).commonDir }.getOrNull()

    private const val ID_LENGTH = 12
}
