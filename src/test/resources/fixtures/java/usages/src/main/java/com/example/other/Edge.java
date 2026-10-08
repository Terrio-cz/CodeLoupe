package com.example.other;

import static com.example.model.Accounts.helper;

import com.example.model.*;
import java.util.List;

public class Edge {
    public List<Integer> refs(List<Point> points) {
        return points.stream().map(Point::area).toList();
    }

    public int cast(Shape s) {
        return s instanceof Circle c ? c.radius() : 0;
    }

    public int self() {
        Point p = new Point(1, 2);
        return p.area();
    }

    public int param(boolean helper) {
        return helper ? helper() : 0;
    }

    public int call() {
        return Accounts.shared();
    }

    public Point one() {
        return new Point(4);
    }

    public int fq(com.example.model.Point p) {
        return p.x;
    }

    public Named lit(String label) {
        return new Named() {
            @Override
            public String label() {
                return "x";
            }

            String show() {
                return label();
            }
        };
    }
}

class Base2 {
    final int n;

    Base2(int n) {
        this.n = n;
    }

    int run() {
        return n;
    }
}

class Outer {
    int helper() {
        return 1;
    }

    String label2 = "outer";

    Base2 f() {
        return new Base2(1) {
            @Override
            int run() {
                helper();
                return label2.length();
            }

            int helper() {
                return 2;
            }

            String label2 = "inner";
        };
    }
}
