package codeloupe.config

import codeloupe.CodeLoupe
import java.io.File
import java.nio.file.Path

/**
 * MCP clients reach the daemon by port alone, so the machine's well-known port belongs to the default home: a
 * daemon with another home (another `config.json`, maybe no policy hook) must use a port of its own.
 */
object PortPolicy {
    /** Why [config] may not bind its port, or null when it may. */
    fun refusal(config: Config, env: Map<String, String> = System.getenv(), os: String = System.getProperty("os.name")): String? {
        if (sameDir(config.home, ConfigLoader.defaultHome(env, os))) return null
        val reserved = setOfNotNull(CodeLoupe.DEFAULT_PORT, ConfigLoader.defaultHomePort(env, os))
        if (config.port !in reserved) return null
        return "port ${config.port} is the default home's; a daemon with home ${config.home} needs its own port (CODELOUPE_PORT)"
    }

    private fun sameDir(a: Path, b: Path): Boolean =
        a.toAbsolutePath().normalize().toString().equals(b.toAbsolutePath().normalize().toString(), ignoreCase = File.separatorChar == '\\')
}
