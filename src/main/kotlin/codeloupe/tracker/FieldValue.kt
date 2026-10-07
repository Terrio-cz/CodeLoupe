package codeloupe.tracker

import kotlinx.serialization.Serializable

/** A custom field with a value; several values are joined with `, `. */
@Serializable
data class FieldValue(val name: String, val value: String)
