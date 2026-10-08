package com.example.shop;

/** Two values. */
public record Pair<A, B>(A first, B second) {
    public Pair {
        if (first == null) {
            throw new IllegalArgumentException("first");
        }
    }

    public static <T> Pair<T, T> twin(T value) {
        return new Pair<>(value, value);
    }

    public <C> Pair<A, C> withSecond(C other) {
        return new Pair<>(first, other);
    }
}
