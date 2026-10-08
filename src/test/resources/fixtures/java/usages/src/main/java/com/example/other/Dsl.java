package com.example.other;

import java.util.List;
import java.util.function.Consumer;

class Builder {
    final Bag items = new Bag();

    int add2() {
        return 1;
    }
}

class Bag {
    int clear3() {
        return 0;
    }
}

class Sack {
    int clear3() {
        return 1;
    }
}

class Use {
    final Sack items = new Sack();

    int add2() {
        return 2;
    }

    void each(List<Builder> builders) {
        builders.forEach(b -> b.add2());
    }

    void receiver(Builder b) {
        Consumer<Builder> consumer = x -> x.items.clear3();
        consumer.accept(b);
        items.clear3();
    }
}
