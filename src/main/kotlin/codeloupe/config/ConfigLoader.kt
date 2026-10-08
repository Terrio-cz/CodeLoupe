package codeloupe.config

import codeloupe.CodeLoupe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path

/**
 * Where CodeLoupe keeps its state and how the daemon is reached. Override with CODELOUPE_HOME /
 * CODELOUPE_PORT / CODELOUPE_ROOT or `<home>/config.json` `{ "port": 47391 }`; `workspaces` is described at [WorkspacesConfig].
 */
object ConfigLoader {
    fun load(env: Map<String, String> = System.getenv(), os: String = System.getProperty("os.name")): Config {
        val home = env["CODELOUPE_HOME"]?.takeIf { it.isNotEmpty() }?.let { Path.of(it) } ?: defaultHome(env, os)
        val file = readFile(home.resolve("config.json"))
        fun number(key: String): Long? = number(file, key)
        fun text(key: String): String? = (file[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
        return Config(
            home = home,
            port = env["CODELOUPE_PORT"]?.toIntOrNull()?.takeIf { it != 0 } ?: number("port")?.toInt() ?: CodeLoupe.DEFAULT_PORT,
            queryTimeoutMs = number("queryTimeoutMs") ?: 10_000,
            buildTimeoutMs = number("buildTimeoutMs") ?: (10 * 60_000),
            buildHeapMb = number("buildHeapMb")?.toInt() ?: 512,
            defaultRoot = env["CODELOUPE_ROOT"]?.takeIf { it.isNotEmpty() } ?: text("defaultRoot"),
            overlayCheckMs = number("overlayCheckMs") ?: 1_000,
            maxParallelQueries = number("maxParallelQueries")?.toInt()?.coerceAtLeast(1) ?: 2,
            largeWorktreeFiles = number("largeWorktreeFiles")?.toInt() ?: 40_000,
            jobs = JobsConfig.parse(file),
            workspaces = WorkspacesConfig.parse(file),
            metrics = MetricsConfig.parse(file),
            budgets = BudgetsConfig.parse(file),
        )
    }

    /** Where the variable import looks: `envImport` of `<home>/config.json`, else the usual places under [userHome]. */
    fun envImport(home: Path, userHome: Path = Path.of(System.getProperty("user.home"))): EnvImportConfig =
        EnvImportConfig.parse(readFile(home.resolve("config.json")), userHome)

    /** The `port` in the default home's `config.json`, or null when it sets none. */
    fun defaultHomePort(env: Map<String, String> = System.getenv(), os: String = System.getProperty("os.name")): Int? =
        number(readFile(defaultHome(env, os).resolve("config.json")), "port")?.toInt()

    fun defaultHome(env: Map<String, String> = System.getenv(), os: String = System.getProperty("os.name")): Path {
        val userHome = Path.of(System.getProperty("user.home"))
        val name = os.lowercase()
        return when {
            name.startsWith("windows") ->
                (env["LOCALAPPDATA"]?.let { Path.of(it) } ?: userHome.resolve("AppData").resolve("Local")).resolve(CodeLoupe.NAME)
            name.startsWith("mac") -> userHome.resolve("Library").resolve("Caches").resolve(CodeLoupe.NAME)
            else -> (env["XDG_CACHE_HOME"]?.let { Path.of(it) } ?: userHome.resolve(".cache")).resolve(CodeLoupe.NAME)
        }
    }

    private fun number(file: JsonObject, key: String): Long? =
        (file[key] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong()?.takeIf { it != 0L }

    private fun readFile(file: Path): JsonObject =
        runCatching { Json.parseToJsonElement(Files.readString(file)).jsonObject }.getOrDefault(JsonObject(emptyMap()))
}
