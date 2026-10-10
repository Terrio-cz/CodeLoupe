package codeloupe.commands

import codeloupe.metrics.Usage

/** What the report needs of a stored run: its price class counts, cost and turns, and what to call it. */
data class CommandRun(
    val id: Long,
    val role: String,
    val file: String,
    val ter: String?,
    val title: String,
    val model: String?,
    val startMs: Long,
    val turns: Int,
    val cost: Long,
    val usage: Usage,
)
