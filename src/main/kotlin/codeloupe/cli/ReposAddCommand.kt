package codeloupe.cli

import codeloupe.JsonFormat
import codeloupe.config.ConfigLoader
import codeloupe.config.RepoConfig
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

class ReposAddCommand : CliktCommand(name = "add") {
    private val paths by argument(help = "Repository folder (the one with .git)").multiple(required = true)
    private val noIndex by option("--no-index", help = "Only write the configuration; do not ask the daemon to index").flag()
    private val json by option("--json", help = "The report as JSON").flag()

    override fun help(context: Context) =
        "Add repositories to config.json (workspaces.repos) and have the daemon start indexing them. A folder that is not a git repository is refused."

    override fun run() {
        val config = ConfigLoader.load()
        val candidates = paths.map { Path.of(it).toAbsolutePath().normalize() }
        val rejected = candidates.mapNotNull { reject(it) }
        val valid = candidates.filter { c -> rejected.none { Path.of(it.path).toAbsolutePath().normalize() == c } }
        val result = try {
            RepoConfig.add(config.home, valid)
        } catch (e: IllegalStateException) {
            throw UsageError(e.message.orEmpty())
        }
        val indexing = if (noIndex) emptyList() else (result.added + result.already).filter { warm(config, it) }
        val report = ReposAddReport(result.added, result.already, rejected, indexing)
        if (json) echo(JsonFormat.json.encodeToString(ReposAddReport.serializer(), report)) else report.lines().forEach(::echo)
        if (rejected.isNotEmpty() && result.added.isEmpty() && result.already.isEmpty()) throw ProgramResult(1)
    }

    private fun reject(path: Path): ReposAddReport.Rejected? = when {
        !Files.isDirectory(path) -> ReposAddReport.Rejected(path.toString(), "not a folder")
        !Files.exists(path.resolve(".git")) -> ReposAddReport.Rejected(path.toString(), "not a git repository (no .git)")
        else -> null
    }

    /** A first query makes the daemon know the repository and start its base build; whether it is ready is the caller's question. */
    private fun warm(config: codeloupe.config.Config, repo: String): Boolean = runCatching {
        DaemonClient(config).call("find", JsonObject(mapOf("root" to JsonPrimitive(repo), "q" to JsonPrimitive("*"), "limit" to JsonPrimitive(1))))
    }.isSuccess
}
