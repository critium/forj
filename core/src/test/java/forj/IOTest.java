package forj;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forj.data.Either;
import forj.data.Unit;
import forj.effect.IO;
import forj.typeclass.Fiber;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

@SuppressWarnings("rawtypes")
class IOTest {

    @Test
    void isLazyAndRerunnable() throws Exception {
        var runs = new AtomicInteger();
        IO<Integer> io = IO.delay(runs::incrementAndGet).map(n -> n * 10);
        assertEquals(0, runs.get());
        assertEquals(10, io.unsafeRunSync());
        assertEquals(20, io.unsafeRunSync());
    }

    @Test
    void deepFlatMapChainsDontOverflowTheStack() throws Exception {
        assertEquals(1_000_000, countTo(0, 1_000_000).unsafeRunSync());
    }

    private static IO<Integer> countTo(int n, int limit) {
        return n == limit ? IO.pure(n) : IO.pure(n + 1).flatMap(m -> countTo(m, limit));
    }

    @Test
    void errorsCanBeRaisedHandledAndAttempted() throws Exception {
        IO<Integer> failing = IO.delay(() -> {
            throw new IOException("boom");
        });
        assertThrows(IOException.class, failing::unsafeRunSync);
        assertEquals(-1, failing.handleErrorWith(e -> IO.pure(-1)).unsafeRunSync());
        var attempted = failing.attempt().unsafeRunSync();
        assertInstanceOf(Either.Left.class, attempted);
        assertEquals("boom", ((Either.Left<Throwable, Integer>) attempted).value().getMessage());
        // handlers further out still see errors raised after an inner handler
        assertEquals("outer", IO.pure(1).handleErrorWith(e -> IO.pure(2))
                .flatMap(n -> IO.<String>raiseError(new IllegalStateException()))
                .handleErrorWith(e -> IO.pure("outer")).unsafeRunSync());
    }

    @Test
    void bracketReleasesWhetherUseSucceedsOrFails() throws Exception {
        List<String> log = new ArrayList<>();
        IO<String> acquire = IO.delay(() -> {
            log.add("open");
            return "file";
        });
        var ok = IO.bracket(acquire, f -> IO.pure(f + " read"), f -> IO.delay(() -> {
            log.add("close");
            return Unit.UNIT;
        }));
        assertEquals("file read", ok.unsafeRunSync());

        var failing = IO.bracket(acquire, f -> IO.<String>raiseError(new IOException("read failed")),
                f -> IO.delay(() -> {
                    log.add("close");
                    return Unit.UNIT;
                }));
        assertThrows(IOException.class, failing::unsafeRunSync);
        assertEquals(List.of("open", "close", "open", "close"), log);
    }

    @Test
    void fibersRunInTheBackgroundAndCanBeJoined() throws Exception {
        IO<Integer> slow = IO.sleep(Duration.ofMillis(100)).map(u -> 42);
        IO<Integer> program = slow.start().flatMap(fiber ->
                IO.pure(1).flatMap(n -> IO.narrow(fiber.join()).map(r -> r + n)));
        assertEquals(43, program.unsafeRunSync());
    }

    @Test
    void cancellingAFiberInterruptsItAndRunsItsFinalizers() throws Exception {
        var released = new CountDownLatch(1);
        IO<Unit> forever = IO.bracket(IO.unit(), u -> IO.sleep(Duration.ofSeconds(30)),
                u -> IO.delay(() -> {
                    released.countDown();
                    return Unit.UNIT;
                }));
        IO<Fiber<IO, Unit>> started = forever.start();
        Fiber<IO, Unit> fiber = started.unsafeRunSync();
        Thread.sleep(50);
        IO.narrow(fiber.cancel()).unsafeRunSync();
        assertTrue(released.await(2, TimeUnit.SECONDS), "finalizer did not run");
        assertThrows(InterruptedException.class, () -> IO.narrow(fiber.join()).unsafeRunSync());
    }

    @Test
    void raceReturnsTheFirstAndCancelsTheOther() throws Exception {
        var loserInterrupted = new CountDownLatch(1);
        IO<String> fast = IO.sleep(Duration.ofMillis(50)).map(u -> "fast");
        IO<String> slow = IO.delay(() -> {
            try {
                Thread.sleep(Duration.ofSeconds(30));
                return "slow";
            } catch (InterruptedException e) {
                loserInterrupted.countDown();
                throw e;
            }
        });
        var winner = IO.race(fast, slow).unsafeRunSync();
        assertEquals(Either.left("fast"), winner);
        assertTrue(loserInterrupted.await(2, TimeUnit.SECONDS), "loser was not cancelled");
    }

    @Test
    void parMap2RunsBothAtOnce() throws Exception {
        IO<Integer> a = IO.sleep(Duration.ofMillis(200)).map(u -> 1);
        IO<Integer> b = IO.sleep(Duration.ofMillis(200)).map(u -> 2);
        long started = System.nanoTime();
        assertEquals(3, IO.parMap2(a, b, Integer::sum).unsafeRunSync());
        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofMillis(380)) < 0);
    }

    @Test
    void asyncWaitsForTheCallback() throws Exception {
        IO<String> fromCallback = IO.async(callback ->
                Thread.ofVirtual().start(() -> callback.accept(Either.right("called back"))));
        assertEquals("called back", fromCallback.unsafeRunSync());
    }

    @Test
    void theInstanceProvidesTheWholeLadder() throws Exception {
        var io = IO.concurrent;
        Kind<IO, Integer> program = io.flatMap(io.delay(() -> 20), n -> io.map(io.pure(n), m -> m + 1));
        assertEquals(21, IO.narrow(program).unsafeRunSync());
        assertEquals(Either.right(5), IO.narrow(io.attempt(io.pure(5))).unsafeRunSync());
    }
}
