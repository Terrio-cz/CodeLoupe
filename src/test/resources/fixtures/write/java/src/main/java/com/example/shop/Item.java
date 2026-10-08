package com.example.shop;

import java.util.Objects;

/**
 * A thing for sale.
 */
public class Item implements Priced {
    private final String name;
    private final long price;
    private int stock;

    public Item(String name, long price) {
        this(name, price, 0);
    }

    public Item(String name, long price, int stock) {
        this.name = Objects.requireNonNull(name);
        this.price = price;
        this.stock = stock;
    }

    public static Item of(String name) {
        return new Item(name, 0);
    }

    public String name() {
        return name;
    }

    @Override
    public long price() {
        return price;
    }

    public int stock() {
        return stock;
    }

    public void restock(int count) {
        stock += count;
    }

    public boolean sellable() {
        return stock > 0 && price > 0;
    }

    @Override
    public String label() {
        return name + " at " + price;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Item item && item.name.equals(name) && item.price == price;
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, price);
    }
}
