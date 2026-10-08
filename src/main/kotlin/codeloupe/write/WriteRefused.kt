package codeloupe.write

/** A write that was not made, and why: the message goes to the caller as the answer. */
class WriteRefused(message: String) : IllegalArgumentException(message)
