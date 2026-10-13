package codeloupe.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * `<home>/config.json` for the commands that change one key of it: read strictly (a file that is not valid JSON is never
 * rewritten, a person's hand edit would be lost) and replaced atomically, pretty-printed.
 */
object ConfigFile {
    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

    fun read(home: Path): JsonObject {
        val file = home.resolve("config.json")
        if (!Files.isRegularFile(file)) return JsonObject(emptyMap())
        return runCatching { Json.parseToJsonElement(Files.readString(file)).jsonObject }
            .getOrElse { throw IllegalStateException("config.json is not valid JSON; fix it by hand first, nothing was changed") }
    }

    fun write(home: Path, config: JsonObject) {
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
