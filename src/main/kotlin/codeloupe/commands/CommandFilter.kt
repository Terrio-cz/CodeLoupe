package codeloupe.commands

/** Which runs a report counts: those that started in [fromMs, toMs], of [role] if given, and only [run] if given (a run id, file name or session id). */
data class CommandFilter(val fromMs: Long, val toMs: Long? = null, val role: String? = null, val run: String? = null)
