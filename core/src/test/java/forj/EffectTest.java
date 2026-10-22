package forj;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import forj.data.Either;
import forj.effect.IO;
import forj.typeclass.Effect;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Laws-lite for Effect, run against every instance: IO and Callable. */
@SuppressWarnings("rawtypes")
class EffectTest {

    private static List<Effect<?>> instances() {
        return List.of(IO.concurrent, Instances.callable);
    }

    @Test
    void runningPureGivesTheValue() throws Exception {
        for (Effect<?> e : instances()) {
            assertEquals(42, run(e, e.pure(42)), e.toString());
        }
    }

    @Test
    void runSyncIsUnsafeRunSync() throws Exception {
        for (Effect<?> e : instances()) {
            var runs = new AtomicInteger();
            var program = flatMapDelay(e, runs);
            assertEquals(2, runSync(e, program), e.toString());
            assertEquals(4, run(e, program), e.toString());
        }
        assertEquals(7, IO.pure(7).runSync());
    }

    @SuppressWarnings("unchecked")
    private static <A> A runSync(Effect<?> e, Kind<?, A> fa) throws Exception {
        return ((Effect<Object>) e).runSync((Kind<Object, A>) fa);
    }

    @Test
    void runningARaisedErrorThrowsIt() {
        for (Effect<?> e : instances()) {
            var boom = new IOException("boom");
            assertSame(boom, assertThrows(IOException.class, () -> run(e, e.raiseError(boom))), e.toString());
        }
    }

    @Test
    void nothingRunsUntilUnsafeRunSyncAndEachRunStartsOver() throws Exception {
        for (Effect<?> e : instances()) {
            var runs = new AtomicInteger();
            var program = flatMapDelay(e, runs);
            assertEquals(0, runs.get(), e.toString());
            assertEquals(2, run(e, program), e.toString());
            assertEquals(4, run(e, program), e.toString());
        }
    }

    private static <F> Kind<F, Integer> flatMapDelay(Effect<F> e, AtomicInteger runs) {
        return e.flatMap(e.delay(runs::incrementAndGet), n -> e.delay(runs::incrementAndGet));
    }

    @Test
    void asyncWaitsForACallbackFromAnotherThread() throws Exception {
        for (Effect<?> e : instances()) {
            assertEquals("called back", run(e, callBack(e, Either.right("called back"))), e.toString());
            var failure = new IllegalStateException("no");
            assertSame(failure, assertThrows(IllegalStateException.class,
                    () -> run(e, callBack(e, Either.left(failure)))), e.toString());
        }
    }

    private static <F> Kind<F, String> callBack(Effect<F> e, Either<Throwable, String> outcome) {
        return e.async(callback -> Thread.ofVirtual().start(() -> callback.accept(outcome)));
    }

    @Test
    void callableIsAnEffectButNotConcurrent() {
        // the instance the resolver finds for Effect<Callable> is the same object as for Sync<Callable>
        Effect<Callable> effect = Instances.callable;
        assertEquals(false, (Object) effect instanceof forj.typeclass.Concurrent);
    }

    @SuppressWarnings("unchecked")
    private static <A> A run(Effect<?> e, Kind<?, A> fa) throws Exception {
        return ((Effect<Object>) e).unsafeRunSync((Kind<Object, A>) fa);
    }
}
