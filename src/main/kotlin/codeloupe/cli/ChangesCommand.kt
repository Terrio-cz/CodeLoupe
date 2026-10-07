package codeloupe.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.serialization.json.JsonPrimitive

class ChangesCommand : ToolCommand("changes") {
    private val bodies by option(help = "Add a line diff of every changed declaration").flag()
    private val limit by option(help = "At most this many declarations (default 60)").int()

    override fun help(context: Context) = "Declarations the worktree changed against the merge-base: + added, ~ body, ^ signature, - removed."

    override fun arguments() = mapOf("bodies" to bodies.takeIf { it }?.let(::JsonPrimitive), "limit" to limit?.let(::JsonPrimitive))
}
