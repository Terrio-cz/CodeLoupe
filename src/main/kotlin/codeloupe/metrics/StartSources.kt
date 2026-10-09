package codeloupe.metrics

/** Which transcript attachments make up each named source of a starting context, and how its characters become tokens. */
object StartSources {
    /** Characters per token, calibrated by least squares of S on the measured characters (CL-31). */
    const val CPT = 3.16

    private val MISC = listOf("environment", "model", "session_context", "date", "credential_org", "total_tokens_reminder", "auto_mode")

    /** Source name in a report -> the transcript attachment (or `body`, `prompt`) its characters come from. */
    private val SOURCES = linkedMapOf(
        "body" to listOf("body"), "claudeMd" to listOf("instructions"), "prompt" to listOf("prompt"), "skills" to listOf("skill_listing"),
        "agentList" to listOf("agent_listing_delta"), "deferred" to listOf("deferred_tools_delta"), "mcpInstr" to listOf("mcp_instructions_delta"),
        "hook" to listOf("hook_additional_context"), "misc" to MISC,
    )

    /** Tokens per source of one run's starting context; `rest` is what no source explains (harness prompt, built-in tools, unmeasured schemas). */
    fun tokens(start: StartCtx, cpt: Double = CPT): Map<String, Double> {
        val out = LinkedHashMap<String, Double>()
        for ((name, keys) in SOURCES) out[name] = keys.sumOf { start.chars[it] ?: 0L }.toDouble() / cpt
        start.mcpSchemas?.let { out["mcpSchemas"] = it / cpt }
        out["rest"] = maxOf(0.0, start.s - out.values.fold(0.0) { n, v -> n + v })
        return out
    }
}
