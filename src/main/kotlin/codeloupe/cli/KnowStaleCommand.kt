package codeloupe.cli

import com.github.ajalt.clikt.core.Context
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class KnowStaleCommand : KnowSubcommand("stale") {
    override fun help(context: Context) = "Read the anchors of every fact and list the ones whose declaration changed or whose file is gone."

    override fun run() {
        val report = call("GET", "/know/stale").second
        val stale = report.getValue("stale").jsonArray
        stale.forEach { echo(it.jsonObject.getValue("line").jsonPrimitive.content) }
        val unreachable = report.getValue("unreachable").jsonPrimitive.content.toInt()
        echo("${stale.size} stale of ${report.getValue("checked").jsonPrimitive.content} anchored facts" + if (unreachable > 0) " ($unreachable could not be read: checkout missing)" else "")
    }
}
