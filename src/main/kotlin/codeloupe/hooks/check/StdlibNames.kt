package codeloupe.hooks.check

/**
 * Names Kotlin and the JDK make visible without an import. The index does not hold them, so a name in this list is never reported
 * as unknown, and a call of one is never judged by the arity of an indexed function of the same name.
 */
internal object StdlibNames {
    fun contains(name: String): Boolean = name in FUNCTIONS || name in TYPES

    private val FUNCTIONS = setOf(
        "listOf", "mutableListOf", "arrayListOf", "emptyList", "listOfNotNull", "mapOf", "mutableMapOf", "hashMapOf", "linkedMapOf", "emptyMap",
        "sortedMapOf", "setOf", "mutableSetOf", "hashSetOf", "linkedSetOf", "emptySet", "sortedSetOf", "arrayOf", "intArrayOf", "longArrayOf",
        "doubleArrayOf", "floatArrayOf", "booleanArrayOf", "charArrayOf", "byteArrayOf", "shortArrayOf", "arrayOfNulls", "emptyArray",
        "println", "print", "readLine", "readln", "readlnOrNull", "require", "requireNotNull", "check", "checkNotNull", "error", "assert",
        "setOfNotNull", "nullsFirst", "nullsLast", "compareValues", "compareValuesBy", "compareBy", "compareByDescending", "naturalOrder", "reverseOrder", "TODO", "run", "with", "apply", "let", "also", "takeIf", "takeUnless", "repeat", "lazy", "lazyOf", "synchronized", "buildString",
        "buildList", "buildMap", "buildSet", "sequence", "sequenceOf", "generateSequence", "emptySequence", "iterator", "to", "maxOf", "minOf",
        "runCatching", "use", "suspend", "lateinit", "enumValues", "enumValueOf", "typeOf",
        "toString", "hashCode", "equals", "component1", "component2", "component3", "invoke", "super", "this",
    )

    private val TYPES = setOf(
        "ThreadLocal", "InheritableThreadLocal", "StackTraceElement", "Runtime", "ClassLoader", "Package", "ConsistentCopyVisibility", "ExposedCopyVisibility",
        "ExperimentalUnsignedTypes", "UnsafeVariance", "ExtensionFunctionType", "ParameterName", "BuilderInference", "OverloadResolutionByLambdaReturnType",
        "MatchResult", "MatchGroup", "RegexOption", "LazyThreadSafetyMode", "CharRange", "IntRange", "LongRange", "IntProgression", "LongProgression",
        "ClosedRange", "Charsets", "Charset", "ArrayList", "HashMap", "HashSet", "LinkedHashMap", "LinkedHashSet", "RandomAccess", "AbstractMutableList",
        "Any", "Unit", "Nothing", "String", "Int", "Long", "Short", "Byte", "Double", "Float", "Boolean", "Char", "Number", "CharSequence",
        "UInt", "ULong", "UShort", "UByte", "Array", "IntArray", "LongArray", "DoubleArray", "FloatArray", "BooleanArray", "CharArray",
        "ByteArray", "ShortArray", "List", "MutableList", "Set", "MutableSet", "Map", "MutableMap", "Collection", "MutableCollection",
        "Iterable", "MutableIterable", "Iterator", "MutableIterator", "ListIterator", "Sequence", "Pair", "Triple", "Result", "Lazy",
        "Comparable", "Comparator", "Function", "Enum", "Annotation", "Regex", "StringBuilder", "Throwable", "Exception", "RuntimeException",
        "Error", "IllegalStateException", "IllegalArgumentException", "UnsupportedOperationException", "NoSuchElementException",
        "IndexOutOfBoundsException", "ConcurrentModificationException", "ArithmeticException", "ClassCastException", "NullPointerException",
        "NumberFormatException", "AssertionError", "OutOfMemoryError", "StackOverflowError", "NotImplementedError", "KotlinNullPointerException",
        "Deprecated", "Suppress", "JvmStatic", "JvmField", "JvmName", "JvmOverloads", "JvmInline", "JvmRecord", "Volatile", "Synchronized",
        "Transient", "Throws", "Strictfp", "OptIn", "RequiresOptIn", "ExperimentalStdlibApi", "PublishedApi", "DslMarker", "Target", "Retention",
        "Repeatable", "MustBeDocumented", "SinceKotlin", "Metadata", "ReplaceWith", "DeprecationLevel", "Object", "Class", "Thread", "Runnable",
        "Math", "System", "Process", "ProcessBuilder", "Character", "Integer", "StringBuffer", "Void", "Override", "FunctionalInterface",
        "SafeVarargs", "SuppressWarnings", "AutoCloseable", "Cloneable", "Record", "Readable", "Appendable", "InterruptedException", "CloneNotSupportedException", "ClassNotFoundException", "SecurityException", "StringIndexOutOfBoundsException",
        "ArrayIndexOutOfBoundsException", "NegativeArraySizeException", "ArrayStoreException", "IllegalMonitorStateException",
    )
}
