package codeloupe.commands

import kotlinx.serialization.Serializable

/** A run that carried a share of a command group: [weighted] is what keeping the results of its [calls] in context cost it. */
@Serializable
data class CommandRunRef(val id: String, val role: String, val ter: String?, val title: String, val calls: Int, val weighted: Long)
