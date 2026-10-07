package codeloupe.query.usages

/**
 * Names something outside the index declares: members of the Kotlin and JDK core types, and every name a file
 * imports from a library. A call of one of them on a receiver of unknown type may be the library's, even when the
 * index declares the name only once.
 */
internal class LibraryNames(private val cache: IndexCache) {
    /** Simple (or alias) name -> fully qualified names files import under it, read in one pass when first needed. */
    private val imported: Map<String, List<String>> by lazy {
        cache.view.imports("i.star = 0").groupBy({ it.alias ?: it.fqn.substringAfterLast('.') }, { it.fqn })
    }

    fun contains(name: String): Boolean =
        name in CORE || imported[name].orEmpty().any { fqn -> cache.named(fqn.substringAfterLast('.')).none { it.fqn == fqn } }

    private companion object {
        val CORE = setOf(
            "toString", "equals", "hashCode", "compareTo", "copy", "component1", "component2", "invoke", "iterator", "next", "hasNext",
            "get", "set", "put", "add", "addAll", "remove", "removeAll", "clear", "contains", "containsKey", "containsAll", "size",
            "isEmpty", "isNotEmpty", "isNullOrEmpty", "isBlank", "isNotBlank", "length", "first", "last", "firstOrNull", "lastOrNull",
            "single", "singleOrNull", "find", "filter", "map", "flatMap", "mapNotNull", "forEach", "any", "all", "none", "count",
            "sum", "sumOf", "max", "min", "sorted", "sortedBy", "reversed", "distinct", "toList", "toSet", "toMap", "toMutableList",
            "toMutableSet", "toMutableMap", "toTypedArray", "joinToString", "plus", "minus", "times", "div", "rem", "not", "and", "or",
            "let", "also", "apply", "run", "with", "use", "close", "start", "stop", "join", "read", "write", "flush", "append",
            "trim", "split", "replace", "substring", "startsWith", "endsWith", "lowercase", "uppercase", "format", "parse", "of",
            "valueOf", "values", "entries", "name", "ordinal", "value", "key", "id", "type", "status", "code", "message", "cause",
            "getValue", "getOrNull", "getOrElse", "getOrDefault", "orEmpty", "until", "step", "to", "build", "create", "from",
            "load", "save", "delete", "update", "insert", "execute", "submit", "send", "receive", "await", "cancel", "lock", "unlock",
            "wait", "notify", "resolve", "exists", "lines", "encode", "decode", "serialize", "deserialize", "now", "between",
            "toLong", "toInt", "toDouble", "allocate", "wrap", "order", "position", "limit", "offset", "register", "handle",
            "process", "accept", "test", "newLine", "interrupt", "print", "println", "body", "path", "url", "method",
        )
    }
}
