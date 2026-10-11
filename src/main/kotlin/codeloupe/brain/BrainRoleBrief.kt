package codeloupe.brain

import kotlinx.serialization.Serializable

/** `briefs.<role>` of `workspace.json`: the topics a role reads first, whether past findings count, how many entries. */
@Serializable
data class BrainRoleBrief(val search: List<String> = emptyList(), val tasks: Boolean = false, val limit: Int = 30, val machine: Boolean = true)
