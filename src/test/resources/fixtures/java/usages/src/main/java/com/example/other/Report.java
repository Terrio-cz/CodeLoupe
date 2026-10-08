package com.example.other;

import com.example.model.*;
import com.example.service.AccountService;

public class Report {
    private final AccountService service;

    public Report(AccountService service) {
        this.service = service;
    }

    public String describe() {
        return "report";
    }

    public void run(Account account, SavingsAccount savings) {
        account.describe();
        savings.describe();
        describe();
        new Account("1", "x").rename("y");
        service.rename("1", "2");
        hidden();
    }

    private static int hidden() {
        return 2;
    }
}
