package com.example.model;

public final class Accounts {
    private Accounts() {
    }

    public static String label(Account account) {
        return "[" + account.id() + "]";
    }

    public static int helper() {
        return 1;
    }

    public static int shared() {
        return 2;
    }
}
