package com.example.mixed

open class Greeter(val prefix: String = "hello") {
    open fun greet(name: String): String = "$prefix $name"

    companion object {
        fun standard(): Greeter = Greeter()
    }
}

fun polite(greeter: Greeter, name: String): String = greeter.greet(name) + "!"

fun fromJava(): String = Shouter().greet("kotlin") + Shouter.loud(Greeter.standard())

class Mixer(private val shouter: Shouter) {
    fun mix(): String = shouter.greet("mix")
}
