package com.example.model

open class Base {
    class Nested {
        companion object {
            fun make() = Nested()
        }
    }
}

class Sub : Base() {
    fun f() = Nested.make()
}

class Point(val x: Int, val y: Int) {
    constructor(both: Int) : this(both, both)

    fun area() = x * y
}

open class Shape

class Circle : Shape() {
    fun radius() = 1
}

interface Named {
    val label: String
}

fun Throwable.report(): String = message.orEmpty()

fun helper() = 1

fun shared() = 2
