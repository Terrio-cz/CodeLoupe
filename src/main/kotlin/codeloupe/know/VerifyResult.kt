package codeloupe.know

import kotlinx.serialization.Serializable

/** A verification: [verified] is false, with the reason in [message], when an anchor could not be fingerprinted. */
@Serializable
data class VerifyResult(val verified: Boolean, val fact: Fact, val message: String? = null)
