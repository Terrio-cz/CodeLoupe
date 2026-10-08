package com.example.model;

public class Base {
    public static class Nested {
        public static Nested make() {
            return new Nested();
        }
    }
}
