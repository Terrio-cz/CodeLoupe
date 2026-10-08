package com.example.service;

import com.example.model.Account;
import com.example.model.Store;
import java.util.ArrayList;
import java.util.List;

public class AccountStore implements Store<Account> {
    private final List<Account> items = new ArrayList<>();

    @Override
    public void save(Account item) {
        items.add(item);
    }

    @Override
    public Account find(String id) {
        return items.stream().filter(a -> a.id().equals(id)).findFirst().orElse(null);
    }

    public List<Account> all() {
        return items;
    }
}
