package codeloupe.lang

import kotlinx.serialization.Serializable

@Serializable
data class ImportFact(val fqn: String, val alias: String?, val star: Boolean)
