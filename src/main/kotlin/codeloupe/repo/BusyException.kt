package codeloupe.repo

/** The answer needs an index that is still being built; the caller should retry shortly. */
class BusyException(message: String) : RuntimeException(message)
