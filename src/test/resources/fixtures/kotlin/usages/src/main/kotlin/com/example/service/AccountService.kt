package com.example.service

import com.example.model.Account
import com.example.model.Level
import com.example.model.Store
import com.example.model.label
import com.example.model.Account as Acc

class AccountStore : Store<Account> {
    private val items = mutableListOf<Account>()

    override fun save(item: Account) {
        items += item
    }

    override fun find(id: String): Account? = items.firstOrNull { it.id == id }

    fun all(): List<Account> = items
}

class AccountService(private val store: AccountStore) {
    fun open(id: String): Account {
        val account = Account.create(id)
        store.save(account)
        return account
    }

    fun rename(id: String, to: String): Account? {
        val found = store.find(id) ?: return null
        return found.rename(to)
    }

    fun describeAll(): List<String> = store.all().map { it.describe() }

    fun labels(): List<String> = store.all().map { it.label() } + "x".label()

    fun check(level: Level) = level.atLeast(Level.HIGH)

    fun aliased(): Acc = Acc("a", "b").apply { rename("c") }

    fun shadow(describe: String): String = describe.length.toString()

    fun cast(x: Any) = (x as Account).describe()

    fun twice(account: Account) = account.rename("d", keepOwner = true)
}
