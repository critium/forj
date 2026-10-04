package forj;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ParTest {

    private static final Duration DELAY = Duration.ofMillis(200);

    private static <A> Callable<A> slow(A value, AtomicInteger runs) {
        return () -> {
            runs.incrementAndGet();
            Thread.sleep(DELAY);
            return value;
        };
    }

    private static Duration time(Callable<?> c) throws Exception {
        long started = System.nanoTime();
        c.call();
        return Duration.ofNanos(System.nanoTime() - started);
    }

    @Test
    void isLazyAndRerunnable() throws Exception {
        var runs = new AtomicInteger();
        Callable<String> both = Par.mapN(slow("a", runs), slow("b", runs), String::concat);
        assertEquals(0, runs.get());

        assertEquals("ab", both.call());
        assertEquals(2, runs.get());
        both.call();
        assertEquals(4, runs.get());
    }

    @Test
    void runsTasksInParallel() throws Exception {
        var runs = new AtomicInteger();
        Callable<List<String>> three = Par.mapN(slow("a", runs), slow("b", runs), slow("c", runs), List::of);
        Callable<Integer> four = Par.mapN(slow(1, runs), slow(2, runs), slow(3, runs), slow(4, runs),
                (a, b, c, d) -> a + b + c + d);

        assertEquals(List.of("a", "b", "c"), three.call());
        assertEquals(10, four.call());
        // sequentially these would take 3 and 4 delays
        assertTrue(time(three).compareTo(DELAY.multipliedBy(2)) < 0);
        assertTrue(time(four).compareTo(DELAY.multipliedBy(2)) < 0);
    }

    @Test
    void traverseKeepsInputOrder() throws Exception {
        var runs = new AtomicInteger();
        Callable<List<Integer>> doubled = Par.traverse(List.of(3, 1, 2), n -> () -> {
            runs.incrementAndGet();
            Thread.sleep(DELAY.toMillis() / n); // finish out of order
            return n * 2;
        });
        assertEquals(List.of(6, 2, 4), doubled.call());
        assertEquals(List.of("a", "b"), Par.sequence(List.of(slow("a", runs), slow("b", runs))).call());
    }

    @Test
    void failureCancelsTheOthersAndRethrowsTheCause() throws Exception {
        var interrupted = new CountDownLatch(1);
        var finished = new AtomicInteger();
        Callable<String> slowSibling = () -> {
            try {
                Thread.sleep(Duration.ofSeconds(5));
                finished.incrementAndGet();
                return "late";
            } catch (InterruptedException e) {
                interrupted.countDown();
                throw e;
            }
        };
        Callable<String> failing = () -> {
            throw new IOException("downstream is down");
        };

        var both = Par.mapN(slowSibling, failing, String::concat);
        long started = System.nanoTime();
        var error = assertThrows(IOException.class, both::call);

        assertEquals("downstream is down", error.getMessage());
        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(2)) < 0);
        assertTrue(interrupted.await(2, TimeUnit.SECONDS), "sibling was not cancelled");
        assertEquals(0, finished.get());
    }
}
