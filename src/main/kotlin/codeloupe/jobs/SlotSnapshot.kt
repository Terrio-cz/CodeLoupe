package codeloupe.jobs

import kotlinx.serialization.Serializable

/**
 * A slot in `/status`: [capacity] is the one in force, [configured] what `config.json` `slots` sets (null: not listed, so 1), [invalid]
 * a value there that is not a positive whole number (ignored). [running] and [waiting] are the ids, [holders] and [queue] the same
 * jobs with since when they hold or wait, and for how many seconds.
 */
@Serializable
data class SlotSnapshot(
    val name: String,
    val capacity: Int,
    val running: List<String>,
    val waiting: List<String>,
    val configured: Int? = null,
    val invalid: String? = null,
    val holders: List<SlotEntry> = emptyList(),
    val queue: List<SlotEntry> = emptyList(),
)
