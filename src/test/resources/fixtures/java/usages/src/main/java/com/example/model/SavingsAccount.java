package com.example.model;

public class SavingsAccount extends Account {
    public SavingsAccount(String id) {
        super(id, "bank");
    }

    @Override
    public String describe() {
        return "savings " + super.describe();
    }
}
