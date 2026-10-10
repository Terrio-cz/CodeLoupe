package codeloupe.metrics

/** Which rule counts the gaps: the one since CL-198, or the one the series began with (`metrics gaps --all`). */
enum class GapRule {
    /** Only fallbacks and empty answers the agent looked up elsewhere; a common word counts only for a code file the answer named. */
    CURRENT,

    /** Every empty answer is a gap, and any later search that mentions the token is a fallback. */
    LEGACY,
}
