package codeloupe.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Edits the repositories of `<home>/config.json` (`workspaces.repos`) and nothing else in it: other keys stay as they are, an
 * entry that is an object (with `roots`) is kept as it is. The file is rewritten only when a repository is added, and not at all when
 * it is not valid JSON, because then a hand-edited file would lose what a person wrote.
 */
object RepoConfig {
    class Result(val added: List<String>, val already: List<String>)

    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

    fun list(home: Path): List<String> = repos(read(home)).map { it.first }

    fun add(home: Path, paths: List<Path>): Result {
        val file = read(home)
        val known = repos(file).map { key(it.first) }.toMutableSet()
        val added = mutableListOf<String>()
        val already = mutableListOf<String>()
        for (path in paths) {
            val text = path.toAbsolutePath().normalize().toString().replace('\\', '/')
            if (known.add(key(text))) added += text else already += text
        }
        if (added.isNotEmpty()) write(home, withRepos(file, added))
        return Result(added, already)
    }

    private fun key(path: String) = path.replace('\\', '/').trimEnd('/').lowercase()

    private fun repos(file: JsonObject): List<Pair<String, JsonObject?>> =
        ((file["workspaces"] as? JsonObject)?.get("repos") as? JsonArray).orEmpty().mapNotNull { entry ->
            when (entry) {
                is JsonPrimitive -> entry.takeIf { it.isString }?.content?.let { it to null }
                is JsonObject -> (entry["path"] as? JsonPrimitive)?.content?.let { it to entry }
                else -> null
            }
        }

    private fun withRepos(file: JsonObject, added: List<String>): JsonObject {
        val workspaces = file["workspaces"] as? JsonObject ?: JsonObject(emptyMap())
        val repos = JsonArray((workspaces["repos"] as? JsonArray).orEmpty() + added.map(::JsonPrimitive))
        return JsonObject(file + ("workspaces" to JsonObject(workspaces + ("repos" to repos))))
    }

    private fun read(home: Path): JsonObject {
        val file = home.resolve("config.json")
        if (!Files.isRegularFile(file)) return JsonObject(emptyMap())
        return runCatching { Json.parseToJsonElement(Files.readString(file)).jsonObject }
            .getOrElse { throw IllegalStateException("config.json is not valid JSON; fix it by hand first, nothing was changed") }
    }

    private fun write(home: Path, config: JsonObject) {
        Files.createDirectories(home)
        val file = home.resolve("config.json")
        val temp = Files.createTempFile(home, "config", ".tmp")
        try {
            Files.writeString(temp, pretty.encodeToString(JsonObject.serializer(), config) + "\n")
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}
