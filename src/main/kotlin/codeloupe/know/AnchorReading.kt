package codeloupe.know

/**
 * What the index and the checkout say about one anchor now. [sig] and [body] are the digests of the matching declarations (all of
 * the file's for a file anchor); null when there are none to digest. [error] is set when the checkout could not be read at all.
 */
data class AnchorReading(
    val fileFound: Boolean,
    val symbolFound: Boolean,
    val sig: String? = null,
    val body: String? = null,
    val error: String? = null,
) {
    val reachable: Boolean get() = error == null

    companion object {
        fun unreachable(message: String) = AnchorReading(fileFound = false, symbolFound = false, error = message)
    }
}
