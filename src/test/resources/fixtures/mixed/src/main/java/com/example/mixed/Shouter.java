package com.example.mixed;

import java.util.List;

public class Shouter extends Greeter {
    public Shouter() {
        super("HEY");
    }

    @Override
    public String greet(String name) {
        return super.greet(name).toUpperCase();
    }

    public static String loud(Greeter greeter) {
        return greeter.greet("java");
    }

    public static List<String> all(List<Greeter> greeters) {
        return greeters.stream().map(g -> g.greet("all")).toList();
    }
}
