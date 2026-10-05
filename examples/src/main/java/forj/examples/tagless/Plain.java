package forj.examples.tagless;

import forj.Kind;
import forj.data.CallableK;
import forj.data.Unit;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/** Interpreters in plain Callable (Sync, but not Concurrent): e.g. for tests or a CLI. */
@SuppressWarnings("rawtypes")
public final class Plain {
    private Plain() {}

    public static final class Warehouse implements Inventory<Callable> {
        private final Map<String, Integer> stock = new HashMap<>();

        public Warehouse(Map<String, Integer> initial) {
            stock.putAll(initial);
        }

        @Override
        public Kind<Callable, Integer> stock(String item) {
            return new CallableK<>(() -> stock.getOrDefault(item, 0));
        }

        @Override
        public Kind<Callable, Unit> reserve(String item, int quantity) {
            return new CallableK<>(() -> {
                stock.merge(item, -quantity, Integer::sum);
                return Unit.UNIT;
            });
        }
    }

    public static final class Bank implements Payments<Callable> {
        @Override
        public Kind<Callable, String> charge(String customer, long cents) {
            return new CallableK<>(() -> "plain-txn");
        }
    }
}
