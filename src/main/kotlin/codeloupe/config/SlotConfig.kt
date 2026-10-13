package codeloupe.config

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path

/** Sets one slot's capacity in `<home>/config.json` (`slots`) and leaves every other key as it is. */
object SlotConfig {
    fun set(home: Path, name: String, capacity: Int) {
        val file = ConfigFile.read(home)
        val slots = (file["slots"] as? JsonObject).orEmpty()
        ConfigFile.write(home, JsonObject(file + ("slots" to JsonObject(slots + (name to JsonPrimitive(capacity))))))
    }
}
