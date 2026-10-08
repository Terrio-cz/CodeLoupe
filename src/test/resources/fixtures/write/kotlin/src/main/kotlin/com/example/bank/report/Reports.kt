package com.example.bank.report

import com.example.bank.Account
import com.example.bank.AccountService
import com.example.bank.Entry
import com.example.bank.Kind
import com.example.bank.Ledger
import com.example.bank.Money as Cash
import com.example.bank.Outcome
import com.example.bank.Registry
import com.example.bank.summary
import com.example.bank.total

class Reports(private val service: AccountService, private val ledger: Ledger) {
    fun statement(id: String): String {
        val account = Registry.find(id) ?: return "no account $id"
        return buildString {
            appendLine(account.summary())
            for (entry in account.entries()) {
                appendLine("  ${entry.kind} ${entry.amount.format()}")
            }
        }
    }

    fun overview(): String {
        val accounts = Registry.all().toList()
        val worth = total(accounts)
        return "${accounts.size} accounts, ${worth.format()}, ${service.count()} transfers, ${ledger.size} entries"
    }

    fun largest(): Account? = Registry.all().maxByOrNull { it.balance }

    fun describeAll(): List<String> = Registry.all().map { account -> account.describe() }

    fun firstEntry(account: Account): Entry? = account.entries().firstOrNull { it.kind == Kind.DEBIT }

    fun zero(): Cash = Cash.ZERO

    fun classify(outcome: Outcome): String = when (outcome) {
        is Outcome.Done -> "done ${outcome.entries.size}"
        is Outcome.Refused -> "refused: ${outcome.reason}"
        Outcome.Unknown -> "unknown"
    }
}
