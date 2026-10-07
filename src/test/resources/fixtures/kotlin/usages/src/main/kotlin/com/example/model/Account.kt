package com.example.model

interface Store<T> {
    fun save(item: T)
    fun find(id: String): T?
}

open class Account(val id: String, val owner: String) {
    open fun describe(): String = "account $id"

    fun rename(to: String): Account = Account(id, to)

    fun rename(to: String, keepOwner: Boolean): Account = if (keepOwner) this else rename(to)

    companion object {
        fun create(id: String): Account = Account(id, "nobody")
    }
}

class SavingsAccount(id: String) : Account(id, "bank") {
    override fun describe(): String = "savings ${super.describe()}"
}

enum class Level {
    LOW,
    HIGH;

    fun atLeast(other: Level) = ordinal >= other.ordinal
}

fun Account.label(): String = "[$id]"

fun String.label(): String = "<$this>"

private fun hidden() = 1

fun visible() = hidden()
