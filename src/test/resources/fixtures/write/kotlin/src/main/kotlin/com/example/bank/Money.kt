package com.example.bank

import kotlin.math.abs

/**
 * An amount of money in cents.
 */
data class Money(val cents: Long) : Comparable<Money> {
    operator fun plus(other: Money): Money = Money(cents + other.cents)

    operator fun minus(other: Money): Money = Money(cents - other.cents)

    override fun compareTo(other: Money): Int = cents.compareTo(other.cents)

    /** Dollars and cents, as in `12.05`. */
    fun format(): String {
        val whole = abs(cents) / 100
        val part = abs(cents) % 100
        val sign = if (cents < 0) "-" else ""
        return "$sign$whole.${part.toString().padStart(2, '0')}"
    }

    fun isZero() = cents == 0L

    companion object {
        val ZERO = Money(0)

        fun of(dollars: Long, cents: Long = 0): Money = Money(dollars * 100 + cents)
    }
}

fun Long.toMoney(): Money = Money(this)

const val MAX_TRANSFER = 1_000_000L
