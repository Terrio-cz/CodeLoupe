package codeloupe.tracker

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads `trackers` from `<home>/config.json`:
 * `{ "trackers": [ { "name": "acme", "type": "youtrack", "url": "https://acme.youtrack.cloud", "projects": ["ABC"],
 * "token": { "env": "YOUTRACK_TOKEN" } or { "dotenv": "<file>", "key": "YOUTRACK_TOKEN" }, "repos": ["<repo>"] } ],
 * "trackerSyncMinutes": 3, "trackerIdleMinutes": 10 }`. Unusable entries are skipped and reported in `problems`.
 */
object TrackerSettingsLoader {
    fun load(home: Path, env: () -> Map<String, String> = System::getenv): TrackerSettings {
        val path = home.resolve("config.json")
        if (!Files.exists(path)) return TrackerSettings(emptyList())
        val file = runCatching { Json.parseToJsonElement(Files.readString(path)).jsonObject }.getOrNull()
            ?: return TrackerSettings(emptyList(), problems = listOf("config.json is not a JSON object; no trackers"))
        val problems = ArrayList<String>()
        val instances = (file["trackers"] as? JsonArray).orEmpty().mapIndexedNotNull { i, entry ->
            val o = entry as? JsonObject
            if (o == null) null.also { problems += "trackers[$i] is not an object" }
            else instance(o, env).onFailure { problems += "trackers[$i]: ${it.message}" }.getOrNull()
        }
        fun minutes(key: String): Long? = (file[key] as? JsonPrimitive)?.content?.toDoubleOrNull()?.takeIf { it > 0 }?.let { (it * 60_000).toLong() }
        val defaults = TrackerSettings(instances, problems = problems)
        return defaults.copy(syncMs = minutes("trackerSyncMinutes") ?: defaults.syncMs, idleMs = minutes("trackerIdleMinutes") ?: defaults.idleMs)
    }

    private fun instance(o: JsonObject, env: () -> Map<String, String>): Result<TrackerInstance> = runCatching {
        val url = o.text("url")?.trimEnd('/') ?: error("no url")
        val uri = runCatching { URI(url) }.getOrNull()
        require(uri != null && uri.scheme in setOf("https", "http") && uri.host != null) { "url is not an http(s) URL" }
        require(uri.rawUserInfo == null) { "url must not carry credentials; use token" }
        val projects = o.texts("projects").map { it.trim().uppercase() }.filter { it.isNotEmpty() }.distinct()
        require(projects.isNotEmpty()) { "no projects" }
        TrackerInstance(
            name = o.text("name") ?: uri.host.substringBefore('.'),
            type = o.text("type") ?: "youtrack",
            url = url,
            projects = projects,
            token = (o["token"] as? JsonObject)?.let { token(it, env) } ?: error("token needs { \"env\": NAME } or { \"dotenv\": file, \"key\": NAME }"),
            repos = o.texts("repos"),
        )
    }

    private fun token(o: JsonObject, env: () -> Map<String, String>): TokenSource? {
        o.text("env")?.let { return TokenSource.Env(it, env) }
        val file = o.text("dotenv") ?: return null
        return TokenSource.DotEnv(Path.of(file), o.text("key") ?: return null)
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    private fun JsonObject.texts(key: String): List<String> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
}
