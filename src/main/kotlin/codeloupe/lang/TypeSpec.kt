package codeloupe.lang

/**
 * Type specs the extractor records and the query side resolves: plain type text (`Foo`, `List<Foo>`), `@line:col`
 * (the declared type of what the reference at that position denotes), `*spec` (an element of `spec`), `a|b` (`a`,
 * else `b`); "" is unknown. A lambda's implicit receiver may also be `&@line:col#p`: the receiver of the function type
 * of parameter `p` (a position, -1 = the last, or a name) of what the call at that position denotes; `|outer` follows
 * when an enclosing lambda's receiver stays in place should that parameter take none.
 */
object TypeSpec {
    const val ELEMENT = '*'
    const val POSITION = '@'
    const val OR = '|'
    const val LAMBDA_RECEIVER = '&'
    const val ARGUMENT = '#'

    private val ITERABLE = Regex("""(?:Mutable)?(?:List|Set|Collection|Iterable|Sequence|Array)<\s*([^,<>]+?)\s*>\??""")

    fun position(line: Int, col: Int) = "$POSITION$line:$col"

    fun lambdaReceiver(callee: String, argument: String) = "$LAMBDA_RECEIVER$callee$ARGUMENT$argument"

    /** `List<Foo>` -> `Foo`; null for text that is not a known collection type. */
    fun elementType(text: String): String? = ITERABLE.matchEntire(text.trim())?.groupValues?.get(1)

    /** The spec of an element of [spec]: direct when the text says it, else deferred with `*`. */
    fun element(spec: String): String = if (spec.isEmpty()) "" else elementType(spec) ?: "$ELEMENT$spec"
}
