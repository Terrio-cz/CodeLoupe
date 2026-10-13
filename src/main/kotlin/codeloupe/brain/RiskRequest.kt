package codeloupe.brain

import kotlinx.serialization.Serializable

/**
 * `POST /brain/risk`: the files a task will touch (paths relative to the code root, or bare file names) and how to weigh the findings the brain holds about them.
 * Everything but [workspace] and [files] has a default, so a workspace without a `risk` block in `workspace.json` still gets an answer.
 */
@Serializable
data class RiskRequest(
    val workspace: String,
    val files: List<String>,
    /** The checkout to read the files from; default the workspace's `canonicalRepo`. */
    val codeRoot: String? = null,
    /** Score as of this moment (ISO date or date-time, UTC): entries written later are left out. Replays a past day; default now. */
    val asOf: String? = null,
    val halfLifeDays: Double = BrainRisk.HALF_LIFE_DAYS,
    /** Weights by severity (`P0`..`P3`, `x`, `gotcha`); a missing key keeps its default. */
    val weights: Map<String, Double> = emptyMap(),
    /** Score from which a file or module is `medium`, and from which it is `high`. */
    val medium: Double = BrainRisk.MEDIUM,
    val high: Double = BrainRisk.HIGH,
    /** How many contributing entries each file and module lists (the heaviest first); the rest is a count. */
    val limit: Int = 8,
)
