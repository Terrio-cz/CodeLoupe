package com.example.shop;

public final class Pricing {
    public static final int PERCENT = 100;

    private Pricing() {
    }

    public static long discount(Item item, int percent) {
        return item.price() * (PERCENT - percent) / PERCENT;
    }

    public static long round(long cents) {
        return (cents + 5) / 10 * 10;
    }

    public static long totalOf(Cart cart) {
        return round(cart.total());
    }
}
