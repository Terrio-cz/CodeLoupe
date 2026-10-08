package codeloupe.docker

import codeloupe.JsonFormat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.time.Instant

/** The slice of the Docker Engine API CodeLoupe uses, over the named pipe or unix socket: no `docker` process, no output parsing. */
class DockerApi(private val endpoint: DockerEndpoint) {
    private val http = DockerHttp(endpoint)

    val address: String get() = endpoint.address

    fun version(): String = json("GET", "/version").jsonObject["Version"]?.jsonPrimitive?.contentOrNull.orEmpty()

    fun containers(): List<DockerObject> = array("/containers/json?all=1").map { c ->
        DockerObject(
            ResourceKind.CONTAINER, c.str("Id").take(SHORT), c.strings("Names").map { it.removePrefix("/") }, c.labels(),
            state = c.str("State").ifEmpty { null }, created = c["Created"]?.jsonPrimitive?.content?.toLongOrNull()?.let { Instant.ofEpochSecond(it).toString() },
        )
    }

    fun images(): List<DockerObject> = array("/images/json").map { i ->
        DockerObject(
            ResourceKind.IMAGE, i.str("Id").removePrefix("sha256:").take(SHORT), i.strings("RepoTags").filter { it != "<none>:<none>" },
            i.labels(), created = i["Created"]?.jsonPrimitive?.content?.toLongOrNull()?.let { Instant.ofEpochSecond(it).toString() },
        )
    }

    fun volumes(): List<DockerObject> =
        (json("GET", "/volumes").jsonObject["Volumes"] as? JsonArray).orEmpty().map { it.jsonObject }.map { v ->
            DockerObject(ResourceKind.VOLUME, v.str("Name"), listOf(v.str("Name")), v.labels(), created = v.str("CreatedAt").ifEmpty { null })
        }

    /** Every network but the three the Engine ships (`bridge`, `host`, `none`). */
    fun networks(): List<DockerObject> = array("/networks").filter { it.str("Name") !in BUILT_IN_NETWORKS }.map { n ->
        DockerObject(ResourceKind.NETWORK, n.str("Id").take(SHORT), listOf(n.str("Name")), n.labels(), created = n.str("Created").ifEmpty { null })
    }

    fun snapshot(): List<DockerObject> = containers() + images() + volumes() + networks()

    /** The labels of the volume [name], null when there is none. */
    fun volumeLabels(name: String): Map<String, String>? {
        val reply = http.request("GET", "/volumes/${encode(name)}")
        if (reply.status == 404) return null
        check200(reply, "inspect volume $name")
        return JsonFormat.json.parseToJsonElement(reply.text).jsonObject.labels()
    }

    /** The labels of the image [reference] (a tag or an id), null when there is none. */
    fun imageLabels(reference: String): Map<String, String>? {
        val reply = http.request("GET", "/images/${encode(reference)}/json")
        if (reply.status == 404) return null
        check200(reply, "inspect image $reference")
        return (JsonFormat.json.parseToJsonElement(reply.text).jsonObject["Config"] as? JsonObject)?.labels().orEmpty()
    }

    /**
     * Creates the volume [name] with the ownership labels. The Engine answers "created" for a name that exists, with the
     * labels it already has, so the volume is looked up first and an existing one is never relabelled.
     */
    fun createVolume(name: String, ownership: Ownership): VolumeOutcome {
        volumeLabels(name)?.let { existing ->
            return if (Ownership.of(existing) == ownership) VolumeOutcome.ALREADY_OURS else VolumeOutcome.EXISTS_OTHER
        }
        val body = buildJsonObject {
            put("Name", name)
            put("Labels", JsonObject(ownership.labels().mapValues { JsonPrimitive(it.value) }))
        }
        val reply = http.request("POST", "/volumes/create", body.toString().toByteArray(Charsets.UTF_8))
        if (reply.status != 201) throw DockerUnavailable("create volume $name: HTTP ${reply.status} ${message(reply)}")
        return VolumeOutcome.CREATED
    }

    /** Stops a running container; a stopped or missing one is fine. The Engine waits [seconds] for it to exit before it kills it. */
    fun stopContainer(id: String, seconds: Int = 10) {
        val reply = http.request("POST", "/containers/${encode(id)}/stop?t=$seconds")
        if (reply.status !in setOf(204, 304, 404)) throw DockerUnavailable("stop container $id: HTTP ${reply.status} ${message(reply)}")
    }

    /** Removes a stopped container and its anonymous volumes. Never forced: a running one is a conflict. */
    fun removeContainer(id: String): Removal = remove("/containers/${encode(id)}?v=1", "remove container $id")

    fun removeNetwork(id: String): Removal = remove("/networks/${encode(id)}", "remove network $id")

    fun removeVolume(name: String): Removal = remove("/volumes/${encode(name)}", "remove volume $name")

    /** Removes an image by id, with the parents nothing else uses. Never forced: one that a container or another tag holds is a conflict. */
    fun removeImage(id: String): Removal = remove("/images/${encode(id)}", "remove image $id")

    // 404: already gone, which is what was wanted. 409 (and 403 for a network with endpoints): in use, retry later.
    private fun remove(path: String, what: String): Removal {
        val reply = http.request("DELETE", path)
        return when (reply.status) {
            200, 204 -> Removal.REMOVED
            404 -> Removal.GONE
            403, 409 -> Removal.Conflict(message(reply))
            else -> throw DockerUnavailable("$what: HTTP ${reply.status} ${message(reply)}")
        }
    }

    private fun array(path: String): List<JsonObject> = json("GET", path).jsonArray.map { it.jsonObject }

    private fun json(method: String, path: String): JsonElement {
        val reply = http.request(method, path)
        check200(reply, "$method $path")
        return JsonFormat.json.parseToJsonElement(reply.text)
    }

    private fun check200(reply: DockerHttp.Reply, what: String) {
        if (reply.status != 200) throw DockerUnavailable("$what: HTTP ${reply.status} ${message(reply)}")
    }

    private fun message(reply: DockerHttp.Reply): String =
        runCatching { JsonFormat.json.parseToJsonElement(reply.text).jsonObject["message"]?.jsonPrimitive?.content }.getOrNull() ?: reply.text.take(200)

    private fun encode(text: String) = URLEncoder.encode(text, Charsets.UTF_8).replace("+", "%20")

    enum class VolumeOutcome { CREATED, ALREADY_OURS, EXISTS_OTHER }

    sealed interface Removal {
        data object REMOVED : Removal
        data object GONE : Removal
        data class Conflict(val message: String) : Removal
    }

    companion object {
        private const val SHORT = 12
        private val BUILT_IN_NETWORKS = setOf("bridge", "host", "none")

        /** The Engine on this machine, or [DockerUnavailable]. */
        fun connect(env: Map<String, String> = System.getenv()): DockerApi =
            DockerApi(DockerEndpoint.firstReachable(DockerEndpoint.candidates(env))).also { it.version() }

        private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

        private fun JsonObject.strings(key: String): List<String> = (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

        // `Labels` is null, not empty, for a resource without any.
        private fun JsonObject.labels(): Map<String, String> =
            (this["Labels"] as? JsonObject).orEmpty().mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }.toMap()
    }
}
