package codeloupe.tracker.youtrack

/** GET against the tracker's REST API; [path] includes the query string. */
fun interface HttpTransport {
    fun get(path: String): HttpReply
}
