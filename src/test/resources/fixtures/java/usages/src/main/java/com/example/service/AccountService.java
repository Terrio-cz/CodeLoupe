package com.example.service;

import static com.example.model.Account.create;

import com.example.model.Account;
import com.example.model.Accounts;
import com.example.model.Level;
import java.util.ArrayList;
import java.util.List;

public class AccountService {
    private final AccountStore store;

    public AccountService(AccountStore store) {
        this.store = store;
    }

    public Account open(String id) {
        Account account = create(id);
        store.save(account);
        return account;
    }

    public Account rename(String id, String to) {
        Account found = store.find(id);
        if (found == null) {
            return null;
        }
        return found.rename(to);
    }

    public List<String> describeAll() {
        List<String> out = new ArrayList<>();
        for (Account a : store.all()) {
            out.add(a.describe());
        }
        return out;
    }

    public List<String> labels() {
        return store.all().stream().map(a -> Accounts.label(a)).toList();
    }

    public boolean check(Level level) {
        return level.atLeast(Level.HIGH);
    }

    public String shadow(String describe) {
        return describe.trim();
    }

    public String cast(Object x) {
        return ((Account) x).describe();
    }

    public Account twice(Account account) {
        return account.rename("d", true);
    }
}
