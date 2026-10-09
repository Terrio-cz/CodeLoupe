package codeloupe.daemon

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray

/**
 * The request body as text. [RequestGuard] refuses a declared length over the limit; a chunked body declares none, so it is read only up to
 * [limit] bytes here and anything longer fails with an [IllegalArgumentException] the route turns into its usual bad-request answer.
 */
suspend fun ApplicationCall.receiveBoundedText(limit: Long = RequestGuard.MAX_BODY): String {
    val bytes = receiveChannel().readRemaining(limit + 1).readByteArray()
    require(bytes.size <= limit) { "request body is larger than $limit bytes" }
    return bytes.toString(Charsets.UTF_8)
}
