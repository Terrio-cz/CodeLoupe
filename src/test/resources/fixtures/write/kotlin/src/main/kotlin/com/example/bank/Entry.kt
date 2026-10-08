package com.example.bank

enum class Kind {
    DEBIT,
    CREDIT,
    ;

    fun sign(): Int = if (this == DEBIT) -1 else 1
}

data class Entry(val account: String, val kind: Kind, val amount: Money) {
    fun signed(): Money = Money(amount.cents * kind.sign())
}

sealed interface Outcome {
    data class Done(val entries: List<Entry>) : Outcome

    data class Refused(val reason: String) : Outcome

    data object Unknown : Outcome
}

interface Ledger {
    val size: Int

    fun record(entry: Entry): Boolean

    fun entries(): List<Entry>
}

class MemoryLedger : Ledger {
    private val items = mutableListOf<Entry>()

    override val size: Int
        get() = items.size

    override fun record(entry: Entry): Boolean {
        items += entry
        return true
    }

    override fun entries(): List<Entry> = items.toList()
}
