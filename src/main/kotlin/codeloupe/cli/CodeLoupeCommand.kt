package codeloupe.cli

import codeloupe.CodeLoupe
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.versionOption

/**
 * `codeloupe` — on-demand code index for AI coding agents. Root defaults to the current directory.
 *
 * [requested] is the first command-line word: when it names a subcommand only that one is built, because building
 * all of them (each with its options) costs start-up time a one-shot CLI call does not have. Anything else (help,
 * a typo, a global option) builds them all, so usage and error texts do not change.
 */
class CodeLoupeCommand(requested: String? = null) : CliktCommand(name = "codeloupe") {
    init {
        versionOption(CodeLoupe.VERSION, names = setOf("--version"))
        subcommands(COMMANDS.filter { it.first == requested }.ifEmpty { COMMANDS }.map { it.second() })
    }

    override fun help(context: Context) = "On-demand code index for AI coding agents. Tools take --root (default: the current directory)."

    override fun run() = Unit

    companion object {
        /** Each subcommand under its own name; `CodeLoupeCommandTest` keeps the names honest. */
        val COMMANDS: List<Pair<String, () -> CliktCommand>> = listOf(
            "daemon" to ::DaemonCommand, "start" to ::StartCommand, "stop" to ::StopCommand, "status" to ::StatusCommand,
            "find" to ::FindCommand, "grep" to ::GrepCommand, "outline" to ::OutlineCommand, "symbol" to ::SymbolCommand, "context" to ::ContextCommand, "usages" to ::UsagesCommand,
            "calls" to ::CallsCommand, "hierarchy" to ::HierarchyCommand, "changes" to ::ChangesCommand,
            "issue" to ::IssueCommand, "task_context" to ::TaskContextCommand, "doc" to ::DocCommand, "tasks" to ::TasksCommand, "similar" to ::SimilarCommand, "task_code" to ::TaskCodeCommand, "code_tasks" to ::CodeTasksCommand,
            "update" to ::UpdateCommand, "job" to ::JobCommand, "webhook" to ::WebhookCommand, "workspaces" to ::WorkspacesCommand, "ws" to ::WsCommand,
            "metrics" to ::MetricsCommand, "mcp-config" to ::McpConfigCommand,
        )
    }
}
