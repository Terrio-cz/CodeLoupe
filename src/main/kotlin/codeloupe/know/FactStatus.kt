package codeloupe.know

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `stale`: an anchor says the fact may no longer hold. `superseded`: another fact replaced it; searches skip it. */
@Serializable
enum class FactStatus(val word: String) {
    @SerialName("active") ACTIVE("active"),
    @SerialName("stale") STALE("stale"),
    @SerialName("superseded") SUPERSEDED("superseded");

    companion object {
        fun parse(word: String): FactStatus =
            entries.firstOrNull { it.word == word.trim().lowercase() } ?: throw IllegalArgumentException("status must be one of ${entries.joinToString("/") { it.word }}: $word")
    }
}
