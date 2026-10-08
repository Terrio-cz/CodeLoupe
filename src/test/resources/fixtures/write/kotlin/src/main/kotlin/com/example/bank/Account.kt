package com.example.bank

/** A bank account with a balance. */
open class Account(val id: String, var balance: Money = Money.ZERO) {
    private val history = mutableListOf<Entry>()

    open fun describe(): String = "account $id: ${balance.format()}"

    fun deposit(amount: Money): Account {
        balance += amount
        history += Entry(id, Kind.CREDIT, amount)
        return this
    }

    fun withdraw(amount: Money): Boolean {
        if (balance < amount) return false
        balance -= amount
        history += Entry(id, Kind.DEBIT, amount)
        return true
    }

    fun entries(): List<Entry> = history.toList()

    companion object {
        fun open(id: String): Account = Account(id)

        fun opened(vararg ids: String): List<Account> = ids.map { open(it) }
    }
}

class SavingsAccount(id: String, private val rate: Double) : Account(id) {
    override fun describe(): String = "savings " + super.describe() + " at $rate"

    fun interest(): Money = Money((balance.cents * rate).toLong())
}

fun Account.summary(): String = "${describe()} (${entries().size} entries)"
