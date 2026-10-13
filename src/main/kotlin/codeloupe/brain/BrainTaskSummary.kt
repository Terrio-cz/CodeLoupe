package codeloupe.brain

/** What a list of task files shows of one: the time of its last entry, its current state and how many entries it holds. */
class BrainTaskSummary(val id: String, val lastAt: String?, val state: Map<String, String>, val entries: Int)
