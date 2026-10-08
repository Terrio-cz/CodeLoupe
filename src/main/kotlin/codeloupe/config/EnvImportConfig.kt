package codeloupe.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/**
 * Where the variable import looks, from `config.json` `envImport`: `roots` as `{ "path": "~/IdeaProjects", "kind": "repositories" }`
 * (kind `home`, `workspaces` or `repositories`) and `exclude`, words that keep a folder out unless the user includes it
 * (a folder whose name holds the word, delimited by anything but letters and digits). Without `roots` the usual places are used.
 */
data class EnvImportConfig(val roots: List<ImportRoot>, val exclude: List<String> = DEFAULT_EXCLUDE) {
    companion object {
        /** Nothing is left out unless the user names it: the words are theirs, in `envImport.exclude`. */
        val DEFAULT_EXCLUDE: List<String> = emptyList()

        fun defaults(userHome: Path): List<ImportRoot> {
            val claude = Files.newDirectoryStream(userHome, ".claude*").use { it.toList() }.sorted().map { ImportRoot(it, ImportRoot.Kind.HOME) }
            return claude + listOf(
                ImportRoot(userHome.resolve("Documents").resolve("Claude"), ImportRoot.Kind.WORKSPACES),
                ImportRoot(userHome.resolve("IdeaProjects"), ImportRoot.Kind.REPOSITORIES),
            ).filter { Files.exists(it.path) }
        }

        fun parse(file: JsonObject, userHome: Path): EnvImportConfig {
            val section = file["envImport"] as? JsonObject
            val roots = (section?.get("roots") as? JsonArray)?.mapNotNull { root(it as? JsonObject ?: return@mapNotNull null, userHome) }
            val exclude = (section?.get("exclude") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.lowercase() }
            return EnvImportConfig(roots?.takeIf { it.isNotEmpty() } ?: defaults(userHome), exclude ?: DEFAULT_EXCLUDE)
        }

        private fun root(entry: JsonObject, userHome: Path): ImportRoot? {
            val text = (entry["path"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() } ?: return null
            val kind = ImportRoot.Kind.entries.firstOrNull { it.name.equals((entry["kind"] as? JsonPrimitive)?.content, ignoreCase = true) } ?: return null
            val path = if (text == "~" || text.startsWith("~/") || text.startsWith("~\\")) userHome.resolve(text.drop(2)) else Path.of(text)
            return ImportRoot(path, kind)
        }
    }
}
