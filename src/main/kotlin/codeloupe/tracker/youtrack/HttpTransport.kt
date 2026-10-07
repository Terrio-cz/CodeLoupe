package codeloupe.tracker.youtrack

import codeloupe.tracker.TrackerException

/** GET and POST against the tracker's REST API; [path] includes the query string. */
fun interface HttpTransport {
    fun get(path: String): HttpReply

    /** POSTs the JSON [body]. */
    fun post(path: String, body: String): HttpReply = throw TrackerException("this transport cannot write")
}
