package codeloupe.brain

import codeloupe.JsonFormat
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

/** `brain.mjs export` parsed: the whole brain of one workspace at one moment. */
@Serializable
data class BrainSnapshot(
    val version: Int = 1,
    val workspace: BrainWorkspaceInfo = BrainWorkspaceInfo(),
    val paths: BrainPaths = BrainPaths(),
    val entries: List<BrainEntry> = emptyList(),
    val findings: List<BrainEntry> = emptyList(),
    val feedback: Map<String, BrainFeedback> = emptyMap(),
) {
    companion object {
        fun parse(text: String): BrainSnapshot = try {
            JsonFormat.json.decodeFromString(serializer(), text)
        } catch (e: SerializationException) {
            throw BrainException(BrainException.Kind.FAILED, "brain.mjs export did not print the expected JSON: ${e.message.orEmpty().lineSequence().first().take(200)}")
        } catch (e: IllegalArgumentException) {
            throw BrainException(BrainException.Kind.FAILED, "brain.mjs export did not print JSON")
        }
    }
}
