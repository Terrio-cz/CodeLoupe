package codeloupe.tracker

/** A tracker call that failed; the message is safe to show (never holds a token). */
class TrackerException(message: String) : RuntimeException(message)
