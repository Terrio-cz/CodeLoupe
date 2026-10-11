package codeloupe.hooks

import java.io.IOException

/**
 * Writes [text] to the standard input of a hook script and closes it. A script that has nothing to ask (no daemon, hooks off,
 * a refused token) exits without reading its input, and the write then meets a closed pipe: that is an answer, not a failure.
 */
internal fun Process.feed(text: String) {
    try {
        outputStream.use { it.write(text.toByteArray()) }
    } catch (_: IOException) {
    }
}
