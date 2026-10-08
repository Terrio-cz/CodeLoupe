package codeloupe.config

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** From `config.json` `secrets`: `rotationDays`, the age after which a stored secret is flagged for rotation (0 = never flag). */
data class SecretsConfig(val rotationDays: Int = 90) {
    companion object {
        fun parse(file: JsonObject): SecretsConfig {
            val days = ((file["secrets"] as? JsonObject)?.get("rotationDays") as? JsonPrimitive)?.content?.toIntOrNull()?.takeIf { it >= 0 }
            return SecretsConfig(days ?: SecretsConfig().rotationDays)
        }
    }
}
