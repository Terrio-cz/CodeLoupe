package com.example.shop;

import static com.example.shop.Pricing.discount;

import java.util.List;
import java.util.function.Function;

public class Shop {
    private final Cart cart;

    public Shop(Cart cart) {
        this.cart = cart;
    }

    public long checkout(int percent) {
        long sum = 0;
        for (Item item : cart.byPrice()) {
            sum += discount(item, percent);
        }
        cart.pay();
        return Pricing.round(sum);
    }

    public Priced gift(long value) {
        return new Priced() {
            @Override
            public long price() {
                return value;
            }
        };
    }

    public List<Long> prices() {
        Function<Item, Long> price = Item::price;
        return cart.byPrice().stream().map(price).toList();
    }

    public boolean done() {
        return cart.status().closed();
    }
}
