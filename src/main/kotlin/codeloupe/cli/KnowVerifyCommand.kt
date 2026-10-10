package codeloupe.cli

import codeloupe.JsonFormat
import codeloupe.know.VerifyResult
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple

class KnowVerifyCommand : KnowSubcommand("verify") {
    private val ids by argument(help = "Fact ids (k17)").multiple(required = true)

    override fun help(context: Context) = "Record that facts hold now: fingerprint their anchors against the current code and clear the stale flag."

    override fun run() {
        var failed = false
        for (id in ids) {
            val result = JsonFormat.json.decodeFromJsonElement(VerifyResult.serializer(), call("POST", "/know/${encode(id)}/verify").second)
            if (result.verified) echo("verified ${result.fact.id} at ${result.fact.verifiedAtCommit?.take(7) ?: "-"}") else echo("${result.fact.id}: ${result.message}", err = true)
            failed = failed || !result.verified
        }
        if (failed) throw ProgramResult(1)
    }
}
