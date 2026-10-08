package codeloupe.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

class DispatchPlanCommand : ToolCommand("dispatch_plan") {
    private val candidate by option(help = "A task id to place (repeat it)").multiple()
    private val epic by option(help = "Epic id: its open leaf tasks are the candidates")
    private val query by option(help = "Tracker filters, e.g. 'project: TER priority: Major'")
    private val slots by option(help = "Free windows (default 4)").int()
    private val width by option(help = "dir (default): one directory is a clash; file: only one file").choice("dir", "file")
    private val replay by option(help = "Plan past work again: resolved tasks count with the files they landed, live worktrees are ignored").flag()
    private val since by option(help = "'none' sends everything again even when you already have it")

    override fun help(context: Context) = "Which ready tasks get a window now so that no two windows touch the same code."

    override fun arguments() = mapOf(
        "candidates" to candidate.takeIf { it.isNotEmpty() }?.let { JsonArray(it.map(::JsonPrimitive)) }, "epic" to epic?.let(::JsonPrimitive),
        "query" to query?.let(::JsonPrimitive), "slots" to slots?.let(::JsonPrimitive), "width" to width?.let(::JsonPrimitive), "replay" to replay.takeIf { it }?.let { JsonPrimitive(true) }, "since" to since?.let(::JsonPrimitive),
    )
}
