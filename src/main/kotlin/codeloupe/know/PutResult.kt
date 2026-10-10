package codeloupe.know

import kotlinx.serialization.Serializable

/** What a write did: `added` a new fact or `updated` the older fact it repeats; [replaced] counts the facts it superseded. */
@Serializable
data class PutResult(val action: String, val fact: Fact, val replaced: Int = 0)
