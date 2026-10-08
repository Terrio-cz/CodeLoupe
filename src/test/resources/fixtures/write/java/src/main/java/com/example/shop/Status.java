package com.example.shop;

public enum Status {
    OPEN("open") {
        @Override
        public boolean closed() {
            return false;
        }
    },
    PAID("paid"),
    SHIPPED("shipped");

    private final String text;

    Status(String text) {
        this.text = text;
    }

    public String text() {
        return text;
    }

    public boolean closed() {
        return true;
    }
}
