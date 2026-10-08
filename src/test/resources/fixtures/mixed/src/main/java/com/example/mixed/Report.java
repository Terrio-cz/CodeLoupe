package com.example.mixed;

public class Report {
    private final Mixer mixer;

    public Report(Mixer mixer) {
        this.mixer = mixer;
    }

    public String text() {
        return mixer.mix() + new Shouter().greet("report") + GreeterKt.polite(new Greeter("x"), "java");
    }
}
