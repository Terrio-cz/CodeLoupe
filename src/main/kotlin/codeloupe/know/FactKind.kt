package codeloupe.know

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class FactKind(val word: String) {
    @SerialName("decision") DECISION("decision"),
    @SerialName("pattern") PATTERN("pattern"),
    @SerialName("blocker") BLOCKER("blocker"),
    @SerialName("environment") ENVIRONMENT("environment"),
    @SerialName("other") OTHER("other");

    companion object {
        fun parse(word: String): FactKind =
            entries.firstOrNull { it.word == word.trim().lowercase() } ?: throw IllegalArgumentException("kind must be one of ${entries.joinToString("/") { it.word }}: $word")
    }
}
