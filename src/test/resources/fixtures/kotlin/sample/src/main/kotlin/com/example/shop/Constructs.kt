@file:JvmName("ConstructsKt")

package com.example.shop

import com.example.shop.model.Order
import com.example.shop.model.*
import com.example.util.Money as Cash
import kotlin.math.max

typealias OrderId = String

/** Top-level constant. */
const val MAX_ITEMS = 10

/**
 * Computes the total.
 */
fun total(orders: List<Order>): Cash = orders.fold(Cash.ZERO) { acc, o -> acc + o.price }

fun String.shout(): String = uppercase() + "!"

val Order.isBig: Boolean
    get() = items.size > MAX_ITEMS

interface Repository<T> {
    fun find(id: OrderId): T?
    fun save(item: T)
}

sealed interface Result {
    data class Ok(val value: Order) : Result
    data object Missing : Result
}

@JvmInline
value class Sku(val raw: String)

enum class Status(val label: String) {
    NEW("new"),
    PAID("paid") {
        override fun next(): Status = SHIPPED
    },
    SHIPPED("shipped");

    open fun next(): Status = this
}

abstract class BaseService(protected val repo: Repository<Order>) {
    abstract fun handle(id: OrderId): Result
}

class OrderService(
    repo: Repository<Order>,
    private val clock: () -> Long = { 0L },
) : BaseService(repo), AutoCloseable {

    private val cache by lazy { mutableMapOf<OrderId, Order>() }

    var lastId: OrderId? = null
        private set

    init {
        require(MAX_ITEMS > 0)
    }

    constructor(repo: Repository<Order>, seed: Long) : this(repo, { seed })

    override fun handle(id: OrderId): Result {
        val order = repo.find(id) ?: return Result.Missing
        lastId = id
        fun local(x: Int) = max(x, 1)
        val ref = ::total
        val other = Order::price
        return Result.Ok(order).also { log("handled ${order.id} ${local(2)}") }
    }

    fun handle(id: OrderId, force: Boolean): Result = if (force) handle(id) else Result.Missing

    infix fun merge(other: OrderService): OrderService = this

    operator fun plus(other: OrderService): OrderService = merge(other)

    override fun close() {}

    companion object Factory {
        fun create(repo: Repository<Order>): OrderService = OrderService(repo)
    }

    inner class Audit {
        fun record() = log("audit")
    }

    private fun log(message: String) = println(message)
}

object Registry {
    val services = mutableListOf<OrderService>()
    fun register(s: OrderService) { services += s }
}

fun main() {
    val svc = OrderService.create(object : Repository<Order> {
        override fun find(id: OrderId): Order? = null
        override fun save(item: Order) {}
    })
    Registry.register(svc)
    svc.handle("1")
    svc handle2 "x"
    println("a".shout())
}

private infix fun OrderService.handle2(id: String) = handle(id)

class `weird name` {
    fun `does something with spaces`() = Unit
}
