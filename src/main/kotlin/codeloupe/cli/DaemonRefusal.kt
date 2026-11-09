package codeloupe.cli

/**
 * The daemon on our port is not one to speak to, and the message says what to do about it: it is wedged, its home is
 * another, `daemon.json` names another process, or it is another version (CL-260). A CLI prints such a refusal as the
 * sentence it is — Clikt prints only its own errors that way, and a stack trace buries the one line that matters.
 * It stays an [IllegalStateException] so that a caller which handles those is unaffected.
 */
class DaemonRefusal(message: String) : IllegalStateException(message)
