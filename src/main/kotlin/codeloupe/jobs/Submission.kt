package codeloupe.jobs

/** The answer to `POST /jobs`: the job, queued or running, or the policy's refusal (nothing was started). */
sealed interface Submission {
    data class Accepted(val job: JobRecord) : Submission

    data class Refused(val decision: PolicyDecision) : Submission
}
