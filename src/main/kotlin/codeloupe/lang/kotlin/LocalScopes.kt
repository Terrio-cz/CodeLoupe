package codeloupe.lang.kotlin

/**
 * Names bound inside code — parameters, lambda parameters, loop and catch variables, local declarations — with
 * their type spec ("" when unknown), innermost frame first. Class members are never bound here. A class body is a
 * barrier: an outer local seen from inside it may also be one of the class's own members.
 */
internal class LocalScopes {
    /** A binding; [beyondClass] when a class body lies between it and the use. */
    data class Binding(val type: String, val beyondClass: Boolean)

    private class Frame(val bindings: HashMap<String, String>, val barrier: Boolean)

    private val frames = ArrayList<Frame>()

    /** Implicit receivers lambdas brought (their specs, "" = unknown type), [CLASS_BODY] where a class body begins. */
    private val receivers = ArrayList<String>()

    /** Spec of the innermost implicit receiver a lambda brought (`x.apply { }`), "" when its type is unknown. */
    val implicitReceiver: String? get() = receivers.lastOrNull { it != CLASS_BODY }

    /** What a plain `this` means when it is a lambda's receiver rather than a class. */
    val thisReceiver: String? get() = receivers.lastOrNull()?.takeIf { it != CLASS_BODY }

    fun lookup(name: String): Binding? {
        var beyond = false
        for (i in frames.indices.reversed()) {
            frames[i].bindings[name]?.let { return Binding(it, beyond) }
            if (frames[i].barrier) beyond = true
        }
        return null
    }

    fun typeOf(name: String): String? = lookup(name)?.type

    /** Binds in the innermost frame; outside any code there is none and nothing is bound. */
    fun bind(name: String, type: String) {
        frames.lastOrNull()?.bindings?.put(name, type)
    }

    fun <T> within(bindings: Map<String, String>, block: () -> T): T = framed(Frame(HashMap(bindings), barrier = false), null, block)

    fun <T> inClassBody(block: () -> T): T = framed(Frame(HashMap(), barrier = true), CLASS_BODY, block)

    fun <T> withReceiver(spec: String?, block: () -> T): T {
        if (spec == null) return block()
        receivers += spec
        try {
            return block()
        } finally {
            receivers.removeLast()
        }
    }

    private fun <T> framed(frame: Frame, receiver: String?, block: () -> T): T {
        frames += frame
        if (receiver != null) receivers += receiver
        try {
            return block()
        } finally {
            if (receiver != null) receivers.removeLast()
            frames.removeLast()
        }
    }

    private companion object {
        /** Not a spec: specs never start with a NUL. */
        const val CLASS_BODY = "\u0000class"
    }
}
