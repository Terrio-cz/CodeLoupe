package com.example.bank

import java.util.Locale

/**
 * Moves money between accounts and keeps the ledger.
 */
class AccountService(private val ledger: Ledger) {
    private var transfers = 0

    fun transfer(from: Account, to: Account, amount: Money): Outcome {
        val problem = validate(from, amount)
        if (problem != null) return Outcome.Refused(problem)
        if (!from.withdraw(amount)) return Outcome.Refused("insufficient funds")
        to.deposit(amount)
        val debit = Entry(from.id, Kind.DEBIT, amount)
        val credit = Entry(to.id, Kind.CREDIT, amount)
        ledger.record(debit)
        ledger.record(credit)
        transfers++
        return Outcome.Done(listOf(debit, credit))
    }

    private fun validate(from: Account, amount: Money): String? {
        if (amount.isZero()) return "nothing to move"
        if (amount.cents > MAX_TRANSFER) return "over the limit"
        return if (from.balance < amount) "insufficient funds" else null
    }

    fun count(): Int = transfers

    fun report(accounts: List<Account>): String {
        fun line(account: Account) = "${account.id.uppercase(Locale.ROOT)} ${account.balance.format()}"
        return accounts.sortedBy { it.id }.joinToString("\n") { line(it) }
    }
}

object Registry {
    private val accounts = mutableMapOf<String, Account>()

    fun register(account: Account): Account {
        accounts[account.id] = account
        return account
    }

    fun find(id: String): Account? = accounts[id]

    fun all(): Collection<Account> = accounts.values
}

fun total(accounts: Collection<Account>): Money = accounts.fold(Money.ZERO) { sum, account -> sum + account.balance }
