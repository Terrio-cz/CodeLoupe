package codeloupe.query

/** [count] references to a type named [name] in the file at [path]. */
class RefCount(val path: String, val name: String, val count: Int)
