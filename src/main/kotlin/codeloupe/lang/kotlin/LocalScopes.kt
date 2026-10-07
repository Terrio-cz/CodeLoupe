package codeloupe.lang.kotlin

/**
 * Names bound inside code — parameters, lambda parameters, loop and catch variables, local declarations — with
 * their type text ("" when unknown), innermost frame first. Class members are never bound here.
 */
internal class LocalScopes {
    private val frames = ArrayList<HashMap<String, String>>()
    private val receivers = ArrayList<String>()

    /** Spec of the innermost implicit receiver a lambda brought (`x.apply { }`), "" when its type is unknown. */
    val implicitReceiver: String? get() = receivers.lastOrNull()

    fun typeOf(name: String): String? {
        for (i in frames.indices.reversed()) frames[i][name]?.let { return it }
        return null
    }

    /** Binds in the innermost frame; outside any code there is none and nothing is bound. */
    fun bind(name: String, type: String) {
        frames.lastOrNull()?.put(name, type)
    }

    fun <T> within(bindings: Map<String, String>, block: () -> T): T {
        frames += HashMap(bindings)
        try {
            return block()
        } finally {
            frames.removeLast()
        }
    }

    fun <T> withReceiver(spec: String?, block: () -> T): T {
        if (spec == null) return block()
        receivers += spec
        try {
            return block()
        } finally {
            receivers.removeLast()
        }
    }
}
