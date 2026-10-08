package com.example.model;

import static java.util.Objects.requireNonNull;

public class Account {
    private final String id;
    private final String owner;

    public Account(String id, String owner) {
        this.id = requireNonNull(id);
        this.owner = owner;
    }

    public String id() {
        return id;
    }

    public String describe() {
        return "account " + id;
    }

    public Account rename(String to) {
        return new Account(id, to);
    }

    public Account rename(String to, boolean keepOwner) {
        return keepOwner ? this : rename(to);
    }

    public static Account create(String id) {
        return new Account(id, "nobody");
    }

    private static int hidden() {
        return 1;
    }

    public static int visible() {
        return hidden();
    }
}
