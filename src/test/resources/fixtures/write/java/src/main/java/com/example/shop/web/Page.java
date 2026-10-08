package com.example.shop.web;

import static com.example.shop.Pricing.round;

import com.example.shop.Cart;
import com.example.shop.Item;
import com.example.shop.Priced;
import com.example.shop.Status;
import java.util.List;

public class Page {
    private final Cart cart;

    public Page(Cart cart) {
        this.cart = cart;
    }

    public String header() {
        Item first = Item.of("first");
        return first.label() + " " + round(cart.total());
    }

    public List<String> lines() {
        return cart.names();
    }

    public boolean finished() {
        return cart.status() == Status.PAID || cart.status().closed();
    }

    public Priced free() {
        return () -> 0;
    }
}
