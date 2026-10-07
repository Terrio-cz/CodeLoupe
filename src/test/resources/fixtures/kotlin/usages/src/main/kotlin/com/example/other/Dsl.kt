package com.example.other

class Builder {
    val items = Bag()

    fun add2() = 1
}

class Bag {
    fun clear3() = 0
}

class Sack {
    fun clear3() = 1
}

fun builder(block: Builder.() -> Unit) = Builder().block()

fun group(block: () -> Unit) = block()

fun opts(onError: () -> Unit = {}, block: Builder.() -> Unit) = Builder().block()

class Use {
    val items = Sack()

    fun add2() = 2

    fun nested() = builder { group { add2() } }

    fun named() = opts(block = { add2() })

    fun receiver(b: Builder) = b.apply { items.clear3() }
}
