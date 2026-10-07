package com.example.other

import com.example.model.*
import com.example.service.AccountService

class Report(private val service: AccountService) {
    fun describe(): String = "report"

    fun run(account: Account, savings: SavingsAccount) {
        account.describe()
        savings.describe()
        describe()
        Account("1", "x").rename(to = "y")
        with(account) { rename("z") }
        service.rename("1", "2")
        hidden()
    }
}

private fun hidden() = 2
