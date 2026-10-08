package codeloupe.docker

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * The compose override that puts the ownership labels on everything a compose project creates: the containers of every
 * service, the images it builds (`build.labels`), and the project's own volumes and networks. External volumes and
 * networks belong to somebody else and are left out. Built from `docker compose config --format json`, so the names
 * are the resolved ones whatever the project's files look like. (Compose has no flag for labels; an extra `-f` file
 * is the one place where it takes them for all three.)
 */
object ComposeOverride {
    fun build(config: JsonObject, ownership: Ownership): JsonObject {
        val labels = JsonObject(ownership.labels().mapValues { JsonPrimitive(it.value) })
        val withLabels = JsonObject(mapOf("labels" to labels))
        fun section(key: String, own: (JsonObject) -> JsonObject?): JsonElement? {
            val entries = (config[key] as? JsonObject).orEmpty().mapNotNull { (name, definition) ->
                own(definition as? JsonObject ?: JsonObject(emptyMap()))?.let { name to it }
            }.toMap()
            return entries.takeIf { it.isNotEmpty() }?.let(::JsonObject)
        }
        val services = section("services") { service ->
            if ("build" in service) JsonObject(mapOf("labels" to labels, "build" to withLabels)) else withLabels
        }
        val volumes = section("volumes") { volume -> withLabels.takeUnless { isExternal(volume) } }
        val networks = section("networks") { network -> withLabels.takeUnless { isExternal(network) } }
        return JsonObject(listOfNotNull(services?.let { "services" to it }, volumes?.let { "volumes" to it }, networks?.let { "networks" to it }).toMap())
    }

    // `external: true`, or the long form `external: { name: … }` that old files still use.
    private fun isExternal(definition: JsonObject): Boolean = definition["external"]?.let { it !is JsonPrimitive || it.jsonPrimitive.content == "true" } == true
}
