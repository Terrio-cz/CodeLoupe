package com.example.shop;

import static java.lang.Math.max;
import static java.util.Collections.*;

import com.example.shop.model.Order;
import com.example.shop.model.*;
import com.example.util.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/** Constants and helpers. */
final class Totals {
    /** Limits of an order. */
    static final int MAX_ITEMS = 10, MIN_ITEMS = 1;

    /**
     * Computes the total.
     */
    static Money total(List<Order> orders) {
        Money sum = Money.ZERO;
        for (Order o : orders) {
            sum = sum.plus(o.price());
        }
        return sum;
    }

    static String shout(String text) {
        return text.toUpperCase() + "!";
    }
}

interface Repository<T> {
    T find(String id);

    void save(T item);
}

sealed interface Result permits Result.Ok, Result.Missing {
    record Ok(Order value) implements Result {
    }

    enum Missing implements Result {
        INSTANCE
    }
}

@interface Marker {
    String value() default "";

    int level() default 1;
}

enum Status {
    NEW("new"),
    PAID("paid") {
        @Override
        Status next() {
            return SHIPPED;
        }
    },
    SHIPPED("shipped");

    private final String label;

    Status(String label) {
        this.label = label;
    }

    Status next() {
        return this;
    }
}

abstract class BaseService {
    protected final Repository<Order> repo;

    BaseService(Repository<Order> repo) {
        this.repo = repo;
    }

    abstract Result handle(String id);
}

@Marker(value = "service", level = 2)
class OrderService extends BaseService implements AutoCloseable {
    private static final List<String> LOG = new ArrayList<>();

    private final Supplier<Long> clock;
    private String lastId;

    static {
        LOG.add("loaded");
    }

    {
        lastId = null;
    }

    OrderService(Repository<Order> repo, Supplier<Long> clock) {
        super(repo);
        this.clock = clock;
    }

    OrderService(Repository<Order> repo, long seed) {
        this(repo, () -> seed);
    }

    @Override
    Result handle(String id) {
        Order order = repo.find(id);
        if (order == null) {
            return Result.Missing.INSTANCE;
        }
        lastId = id;
        int bounded = max(order.items().size(), 1);
        Supplier<Order> ref = () -> order;
        Function<Order, Money> price = Order::price;
        log("handled " + order.id() + " " + bounded);
        return new Result.Ok(order);
    }

    Result handle(String id, boolean force) {
        return force ? handle(id) : Result.Missing.INSTANCE;
    }

    OrderService merge(OrderService other) {
        return this;
    }

    @Override
    public void close() {
    }

    static OrderService create(Repository<Order> repo) {
        return new OrderService(repo, 0L);
    }

    @SafeVarargs
    static <T extends Comparable<T>> T biggest(T first, T... rest) {
        T best = first;
        for (T candidate : rest) {
            if (candidate.compareTo(best) > 0) {
                best = candidate;
            }
        }
        return best;
    }

    class Audit {
        void record() {
            log("audit");
        }
    }

    private void log(String message) {
        System.out.println(message);
    }
}

final class Registry {
    static final List<OrderService> SERVICES = new ArrayList<>();

    static void register(OrderService s) {
        SERVICES.add(s);
    }
}

class Main {
    public static void main(String[] args) {
        OrderService svc = OrderService.create(new Repository<Order>() {
            @Override
            public Order find(String id) {
                return null;
            }

            @Override
            public void save(Order item) {
            }
        });
        Registry.register(svc);
        svc.handle("1");
        System.out.println(Totals.shout("a"));
    }
}
