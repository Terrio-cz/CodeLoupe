package codeloupe.query.usages

/**
 * Member names common in the Kotlin, JVM, coroutines, serialization, Ktor and Exposed APIs. A call of one of them on
 * a receiver of unknown type may well be the library's, even when the index declares the name only once.
 */
internal object LibraryNames {
    fun contains(name: String) = name in NAMES

    private val NAMES = setOf(
        // Kotlin and JVM
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
        "load", "save", "delete", "update", "insert", "execute", "submit", "send", "receive", "await", "cancel", "launch",
        "async", "emit", "collect", "lock", "unlock", "wait", "notify", "resolve", "toPath", "toFile", "exists", "readText",
        "writeText", "readBytes", "lines", "encode", "decode", "encodeToString", "decodeFromString", "serialize", "deserialize",
        "toInstant", "toEpochMilli", "now", "plusDays", "minusDays", "between", "toLong", "toInt", "toDouble", "toBigDecimal",
        "allocate", "wrap", "order", "position", "limit", "offset", "register", "handle", "process", "accept", "test", "apply",
        // Ktor
        "call", "respond", "respondText", "respondBytes", "receiveText", "request", "response", "parameters", "queryParameters",
        "headers", "header", "route", "routing", "get", "post", "put", "patch", "delete", "install", "application", "attributes",
        "principal", "authenticate", "sessions", "redirect", "body", "bodyAsText", "setBody", "url", "method", "uri", "path",
        // Exposed and JDBC
        "select", "selectAll", "where", "andWhere", "orderBy", "groupBy", "having", "slice", "batchInsert", "upsert",
        "deleteWhere", "insertAndGetId", "exec", "transaction", "prepareStatement", "executeQuery", "executeUpdate", "setString",
        "setLong", "setInt", "getString", "getLong", "getInt", "commit", "rollback", "eq", "neq", "less", "greater", "isNull",
        "isNotNull", "inList", "like", "alias", "table", "columns", "references", "index", "uniqueIndex", "default", "nullable",
    )
}
