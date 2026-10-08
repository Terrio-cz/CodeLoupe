package demo;

import java.util.List;

public class Mailer {
    private final List<String> sent = new java.util.ArrayList<>();

    /** Sends one message and remembers the recipient. */
    public int send(String to, String body) {
        String subject = body.length() > 10 ? body.substring(0, 10) : body;
        sent.add(to);
        return deliver(to, subject);
    }

    public int count() {
        int n = sent.size();
        String text = n;
        return text.length() + missing;
    }
}
