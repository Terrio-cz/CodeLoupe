package com.example.shop;

/** Something that has a price in cents. */
public interface Priced {
    long price();

    default String label() {
        return "priced " + price();
    }
}
