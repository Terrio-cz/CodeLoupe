package codeloupe.uiapi

import io.ktor.http.HttpStatusCode

/** An answer the UI API gives instead of data: `{ "error": { "code", "message" } }` with [status]. */
class UiApiException(val code: String, message: String, val status: HttpStatusCode) : RuntimeException(message) {
    companion object {
        fun badRequest(message: String) = UiApiException("bad_request", message, HttpStatusCode.BadRequest)

        fun notFound(message: String) = UiApiException("not_found", message, HttpStatusCode.NotFound)

        fun busy(message: String) = UiApiException("busy", message, HttpStatusCode.ServiceUnavailable)

        fun unavailable(message: String) = UiApiException("unavailable", message, HttpStatusCode.ServiceUnavailable)
    }
}
