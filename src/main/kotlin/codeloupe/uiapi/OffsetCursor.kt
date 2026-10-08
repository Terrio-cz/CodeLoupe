package codeloupe.uiapi

import java.util.Base64

/** The opaque `cursor` of a page: the offset of its first item. */
internal object OffsetCursor {
    fun of(offset: Int): String = Base64.getUrlEncoder().withoutPadding().encodeToString("o:$offset".toByteArray())

    fun offset(cursor: String?): Int = cursor?.let {
        runCatching { String(Base64.getUrlDecoder().decode(it)).removePrefix("o:").toInt().also { n -> require(n >= 0) } }
            .getOrElse { throw UiApiException.badRequest("bad cursor") }
    } ?: 0

    fun limit(text: String?, default: Int, max: Int): Int =
        text?.let { it.toIntOrNull()?.takeIf { n -> n in 1..max } ?: throw UiApiException.badRequest("limit must be 1..$max") } ?: default
}
