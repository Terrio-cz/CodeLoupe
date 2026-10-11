package codeloupe.brain

import kotlinx.serialization.Serializable

/**
 * One list of a ranked answer. [scopes] are the topics to look in (empty = all), [levels] `workspace` / `machine` (empty = both), [tasks]
 * adds the finding lines of past tasks, [kinds] restricts the entry kinds, [anchoredOnly] keeps only entries tied to the question's files or
 * symbols (not by module, caller or task alone).
 */
@Serializable
data class BrainSection(
    val name: String = "facts",
    val scopes: List<String> = emptyList(),
    val tasks: Boolean = false,
    val limit: Int = 30,
    val levels: List<String> = emptyList(),
    val kinds: List<String> = emptyList(),
    val anchoredOnly: Boolean = false,
)
