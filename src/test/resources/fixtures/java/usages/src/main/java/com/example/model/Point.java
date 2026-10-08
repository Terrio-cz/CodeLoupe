package com.example.model;

public class Point {
    public final int x;
    public final int y;

    public Point(int x, int y) {
        this.x = x;
        this.y = y;
    }

    public Point(int both) {
        this(both, both);
    }

    public int area() {
        return x * y;
    }
}
