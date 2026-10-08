package com.example.shop;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class Cart {
    private final List<Item> items = new ArrayList<>();
    private Status status = Status.OPEN;

    public Cart add(Item item) {
        items.add(item);
        return this;
    }

    public Cart add(String name, long price) {
        return add(new Item(name, price));
    }

    public long total() {
        long sum = 0;
        for (Item item : items) {
            sum += item.price();
        }
        return sum;
    }

    public List<String> names() {
        return items.stream().map(item -> item.name()).sorted().toList();
    }

    public List<Item> byPrice() {
        List<Item> copy = new ArrayList<>(items);
        copy.sort(new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                return Long.compare(a.price(), b.price());
            }
        });
        return copy;
    }

    public Status status() {
        return status;
    }

    public void pay() {
        status = Status.PAID;
    }

    public Line first() {
        return new Line(items.get(0), 1);
    }

    public class Line {
        private final Item item;
        private final int count;

        Line(Item item, int count) {
            this.item = item;
            this.count = count;
        }

        public long cost() {
            return item.price() * count;
        }
    }

    public static class Builder {
        private final Cart cart = new Cart();

        public Builder with(String name, long price) {
            cart.add(name, price);
            return this;
        }

        public Cart build() {
            return cart;
        }
    }
}
