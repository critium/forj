package forj.examples.tagless;

import forj.data.Unit;
import forj.effect.IO;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Interpreters in IO: the effect a real service would run in. */
@SuppressWarnings("rawtypes")
public final class Live {
    private Live() {}

    public static final class Warehouse implements Inventory<IO> {
        private final Map<String, Integer> stock = new ConcurrentHashMap<>();

        public Warehouse(Map<String, Integer> initial) {
            stock.putAll(initial);
        }

        @Override
        public IO<Integer> stock(String item) {
            return IO.delay(() -> stock.getOrDefault(item, 0));
        }

        @Override
        public IO<Unit> reserve(String item, int quantity) {
            return IO.delay(() -> {
                stock.merge(item, -quantity, Integer::sum);
                return Unit.UNIT;
            });
        }

        public int left(String item) {
            return stock.getOrDefault(item, 0);
        }
    }

    public static final class Bank implements Payments<IO> {
        private final AtomicInteger next = new AtomicInteger();
        public final List<String> charges = new CopyOnWriteArrayList<>();

        @Override
        public IO<String> charge(String customer, long cents) {
            return IO.delay(() -> {
                charges.add(s"$customer:$cents");
                return s"txn-${next.incrementAndGet()}";
            });
        }
    }
}
