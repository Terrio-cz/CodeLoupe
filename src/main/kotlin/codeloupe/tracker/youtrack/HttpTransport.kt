package codeloupe.tracker.youtrack

import codeloupe.tracker.TrackerException

/** GET and POST against the tracker's REST API; [path] includes the query string. */
fun interface HttpTransport {
    fun get(path: String): HttpReply

    /** POSTs the JSON [body]. */
    fun post(path: String, body: String): HttpReply = throw TrackerException("this transport cannot write")

    /** POSTs [bytes] as the file part `file` of a multipart form, named [fileName]. */
    fun postFile(path: String, fileName: String, bytes: ByteArray): HttpReply = throw TrackerException("this transport cannot upload")
}
