package forj.examples.tagless;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forj.Kind;
import forj.data.CallableK;
import forj.effect.IO;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

@SuppressWarnings("rawtypes")
class CheckoutTest {

    private static final Order PENS = new Order("ana", "pen", 3, 150);

    @Test
    void runsInIO() throws Exception {
        var warehouse = new Live.Warehouse(Map.of("pen", 10));
        var bank = new Live.Bank();
        given Inventory<IO> inventory = warehouse;
        given Payments<IO> payments = bank;

        IO<Receipt> program = Checkout.checkout(PENS);      // F = IO, from the target type
        assertEquals(List.of(), bank.charges);               // nothing has run yet

        Receipt receipt = program.unsafeRunSync();
        assertEquals("3 x pen for ana (txn-1)", receipt.summary());
        assertEquals(List.of("ana:450"), bank.charges);
        assertEquals(7, warehouse.left("pen"));
    }

    @Test
    void failsInIOWhenOutOfStock() {
        var bank = new Live.Bank();
        given Inventory<IO> inventory = new Live.Warehouse(Map.of("pen", 2));
        given Payments<IO> payments = bank;

        IO<Receipt> program = Checkout.checkout(PENS);
        var error = assertThrows(OutOfStock.class, program::unsafeRunSync);
        assertEquals("pen: wanted 3, only 2 left", error.getMessage());
        assertEquals(List.of(), bank.charges);               // never charged
    }

    @Test
    void runsTheSameProgramInPlainCallable() throws Exception {
        given Inventory<Callable> inventory = new Plain.Warehouse(Map.of("pen", 10));
        given Payments<Callable> payments = new Plain.Bank();

        Kind<Callable, Receipt> program = Checkout.checkout(PENS);
        assertEquals("plain-txn", CallableK.narrow(program).call().transaction());
    }

    @Test
    void ladderFunctionsWorkForAnyEffectWithEnoughCapability() throws Exception {
        given Inventory<IO> inventory = new Live.Warehouse(Map.of("pen", 10));
        given Payments<IO> payments = new Live.Bank();

        IO<Receipt> order = Checkout.checkout(PENS);
        IO<String> described = Ladder.describe(order);                        // Functor<IO>
        assertEquals("3 x pen for ana (txn-1)", described.unsafeRunSync());

        var attempts = new AtomicInteger();
        IO<String> flaky = IO.delay(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw new IllegalStateException("not yet");
            }
            return "third time";
        });
        IO<String> retried = Ladder.retry(flaky, 5);                           // MonadError<IO, Throwable>
        assertEquals("third time", retried.unsafeRunSync());

        IO<String> slow = IO.sleep(Duration.ofSeconds(5)).map(u -> "too late");
        IO<String> limited = Ladder.withTimeout(slow, Duration.ofMillis(100)); // Concurrent<IO>
        long started = System.nanoTime();
        assertThrows(TimeoutException.class, limited::unsafeRunSync);
        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(2)) < 0);
    }
}
