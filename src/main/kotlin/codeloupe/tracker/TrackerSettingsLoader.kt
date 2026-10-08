package codeloupe.tracker

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import codeloupe.accounts.AccountsFile
import codeloupe.secrets.SecretStore
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
    fun load(home: Path, store: () -> SecretStore? = { null }, env: () -> Map<String, String> = System::getenv): TrackerSettings {
        val path = home.resolve("config.json")
        val problems = ArrayList<String>()
        val accounts = accountInstances(home, store, problems)
        if (!Files.exists(path)) return TrackerSettings(accounts, problems = problems)
        val file = runCatching { Json.parseToJsonElement(Files.readString(path)).jsonObject }.getOrNull()
            ?: return TrackerSettings(accounts, problems = problems + "config.json is not a JSON object; its trackers are ignored")
        val configured = (file["trackers"] as? JsonArray).orEmpty().mapIndexedNotNull { i, entry ->
            val o = entry as? JsonObject
            if (o == null) null.also { problems += "trackers[$i] is not an object" }
            else instance(o, env).onFailure { problems += "trackers[$i]: ${it.message}" }.getOrNull()
        }
        // A tracker of config.json keeps its name; an account of the same name is the one that is skipped.
        val instances = configured + accounts.filter { a -> configured.none { it.name == a.name }.also { free -> if (!free) problems += "account ${a.name} has the name of a tracker in config.json; skipped" } }
        fun minutes(key: String): Long? = (file[key] as? JsonPrimitive)?.content?.toDoubleOrNull()?.takeIf { it > 0 }?.let { (it * 60_000).toLong() }
        val defaults = TrackerSettings(instances, problems = problems)
        return defaults.copy(syncMs = minutes("trackerSyncMinutes") ?: defaults.syncMs, idleMs = minutes("trackerIdleMinutes") ?: defaults.idleMs)
    }

    /** The YouTrack accounts of `accounts.json` (the desktop app's), whose tokens are names in the encrypted store. */
    private fun accountInstances(home: Path, store: () -> SecretStore?, problems: MutableList<String>): List<TrackerInstance> =
        AccountsFile.read(home).youtrack.mapNotNull { a ->
            val uri = runCatching { URI(a.url.trimEnd('/')) }.getOrNull()
            val projects = a.projects.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.distinct()
            when {
                uri == null || uri.scheme !in setOf("https", "http") || uri.host == null || uri.rawUserInfo != null -> null.also { problems += "account ${a.id}: url is not a plain http(s) URL" }
                projects.isEmpty() -> null.also { problems += "account ${a.id}: no projects" }
                else -> TrackerInstance(a.id, "youtrack", a.url.trimEnd('/'), projects, TokenSource.Stored(a.token, store, "tracker mirror: ${a.id}"))
            }
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
