package com.example.other

import com.example.model.*

class Edge {
    fun refs(points: List<Point>) = points.map(Point::area)

    fun cast(s: Shape) = if (s is Circle) s.radius() else 0

    fun self() = Point(1, 2).apply { this.area() }

    fun failure(e: IllegalStateException) = e.report()

    fun param(helper: Boolean) = if (helper) helper() else 0

    fun call() = shared()

    fun one() = Point(4)

    fun fq(p: com.example.model.Point) = p.x

    fun lit(label: String) = object : Named {
        override val label = "x"

        fun show() = label
    }

    fun dsl() = build { area() }
}

fun build(block: Point.() -> Unit) = Point(0, 0).block()
