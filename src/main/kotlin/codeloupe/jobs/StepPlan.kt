package codeloupe.jobs

/** What a finished job's completion steps lead to: actions to take now, then at most one follow-up job. */
class StepPlan private constructor(val now: List<Action>, val next: Action.RunJob?, val rest: List<Action>) {
    companion object {
        /**
         * Walks [steps] in order. Each condition is judged when its step is reached, against [finished]; a job step
         * ends the walk and [rest] — the steps after it, unjudged — continues once that job ends. With [startJobs]
         * false (a cancelled job) the walk stops at the first job step: what follows it may report on work that never ran.
         */
        fun of(steps: List<Action>, finished: JobRecord, startJobs: Boolean): StepPlan {
            val now = mutableListOf<Action>()
            for ((i, step) in steps.withIndex()) {
                if (step.condition?.holds(finished) == false) continue
                if (step !is Action.RunJob) {
                    now += step
                    continue
                }
                return if (startJobs) StepPlan(now, step, steps.drop(i + 1)) else StepPlan(now, null, emptyList())
            }
            return StepPlan(now, null, emptyList())
        }
    }
}
