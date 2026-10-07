package codeloupe.jobs

/** The policy hook's answer for one command. */
data class PolicyDecision(val verdict: Verdict, val reason: String) {
    enum class Verdict { ALLOW, ASK, DENY }

    val allowed: Boolean get() = verdict == Verdict.ALLOW
}
