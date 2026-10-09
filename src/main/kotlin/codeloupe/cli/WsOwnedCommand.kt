package codeloupe.cli

import codeloupe.JsonFormat
import codeloupe.config.ConfigLoader
import codeloupe.docker.DockerApi
import codeloupe.docker.DockerUnavailable
import codeloupe.docker.Ownership
import codeloupe.docker.InstallId
import codeloupe.workspace.WorkspaceIdentity
import codeloupe.workspace.WorkspaceList
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.nio.file.Path

/**
 * A `ws` command that does something in a workspace: [dir] (default the current directory) names it through the workspace
 * registry of the daemon, and the labels come from there. What the wrapped docker command prints stays on standard
 * output; CodeLoupe's own lines go to standard error.
 */
abstract class WsOwnedCommand(name: String) : CliktCommand(name = name) {
    protected val dir by option("--dir", help = "A directory of the workspace (default: the current directory)").default(".")

    protected val workDir: Path get() = Path.of(dir).toAbsolutePath().normalize()

    protected fun note(text: String) = echo(text, err = true)

    protected fun engine(): DockerApi = try {
        DockerApi.connect()
    } catch (e: DockerUnavailable) {
        fail(e.message.orEmpty())
    }

    /** The ownership of [workDir]; the command ends with a message when it is in no workspace. */
    protected fun ownership(): Ownership {
        val (status, body) = DaemonClient(ConfigLoader.load()).send("GET", "/workspaces?repo=" + URLEncoder.encode(workDir.toString(), Charsets.UTF_8), timeout = null)
        if (status != 200) fail(body["error"]?.jsonPrimitive?.content ?: "HTTP $status")
        return try {
            WorkspaceIdentity.pick(JsonFormat.json.decodeFromJsonElement(WorkspaceList.serializer(), body), workDir, InstallId.of(ConfigLoader.load().home))
        } catch (e: IllegalArgumentException) {
            fail(e.message.orEmpty())
        }.also { note("workspace ${it.workspace} of ${it.repo}" + if (it.task.isNotEmpty()) " (task ${it.task})" else "") }
    }

    protected fun fail(message: String): Nothing {
        note(message)
        throw ProgramResult(1)
    }

    /** Ends the command with [code] unless it is 0. */
    protected fun exit(code: Int) {
        if (code != 0) throw ProgramResult(code)
    }
}
