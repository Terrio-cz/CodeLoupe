package com.example.model;

public enum Level {
    LOW,
    HIGH;

    public boolean atLeast(Level other) {
        return ordinal() >= other.ordinal();
    }
}
