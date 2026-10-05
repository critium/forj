package forj.effect;

import forj.Given;
import forj.Kind;
import forj.data.Either;
import forj.data.Unit;
import forj.typeclass.Concurrent;
import forj.typeclass.Fiber;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.StructuredTaskScope;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A lazy, re-runnable description of a computation that may perform side effects, fail, or
 * wait: cats-effect 2's {@code IO}, on virtual threads.
 *
 * <p>Nothing happens until {@link #unsafeRunSync()}, and each run starts over. The interpreter
 * is a loop with an explicit stack, so long {@code flatMap} chains (recursive loops written
 * with {@code flatMap}) don't overflow the Java stack. Blocking inside {@code delay} is fine:
 * runs and fibers are virtual threads. Cancelling a fiber interrupts its thread; the
 * interpreter checks for interruption between steps and fails with
 * {@link InterruptedException}, so {@code bracket} releases still run.
 *
 * <p>{@code IO} is its own witness: {@code IO<A>} is a {@code Kind<IO, A>}, and its instance,
 * {@link #concurrent}, provides the whole ladder up to {@link Concurrent}.
 */
@SuppressWarnings("rawtypes")
public abstract sealed class IO<A> implements Kind<IO, A> {

    private IO() {}

    // ------------------------------------------------------------------ building

    public static <A> IO<A> pure(A a) {
        return new Pure<>(a);
    }

    public static IO<Unit> unit() {
        return pure(Unit.UNIT);
    }

    /** Suspends a side effect; an exception becomes a failed IO. */
    public static <A> IO<A> delay(Callable<? extends A> thunk) {
        return new Delay<>(thunk);
    }

    /** Suspends building an IO, e.g. for recursion. */
    public static <A> IO<A> suspend(Callable<? extends IO<A>> thunk) {
        return new Suspend<>(thunk);
    }

    public static <A> IO<A> raiseError(Throwable error) {
        return new RaiseError<>(error);
    }

    /** Waits for a callback-based computation; the first value passed to the callback wins. */
    public static <A> IO<A> async(Consumer<Consumer<Either<Throwable, A>>> register) {
        return new Async<>(register);
    }

    public static IO<Unit> sleep(Duration duration) {
        return delay(() -> {
            Thread.sleep(duration);
            return Unit.UNIT;
        });
    }

    /** The IO behind a {@code Kind<IO, A>}. */
    public static <A> IO<A> narrow(Kind<IO, A> kind) {
        return (IO<A>) kind;
    }

    public <B> IO<B> flatMap(Function<? super A, ? extends Kind<IO, B>> f) {
        return new FlatMap<>(this, f);
    }

    public <B> IO<B> map(Function<? super A, ? extends B> f) {
        return flatMap(a -> pure(f.apply(a)));
    }

    public IO<A> handleErrorWith(Function<? super Throwable, ? extends Kind<IO, A>> handler) {
        return new HandleErrorWith<>(this, handler);
    }

    public IO<Either<Throwable, A>> attempt() {
        return this.<Either<Throwable, A>>map(Either::right).handleErrorWith(e -> pure(Either.left(e)));
    }

    /** Runs {@code finalizer} after this, whether it succeeds or fails. */
    public IO<A> guarantee(Kind<IO, Unit> finalizer) {
        return handleErrorWith(e -> narrow(finalizer).flatMap(u -> raiseError(e)))
                .flatMap(a -> narrow(finalizer).map(u -> a));
    }

    public static <R, B> IO<B> bracket(Kind<IO, R> acquire, Function<? super R, ? extends Kind<IO, B>> use,
                                       Function<? super R, ? extends Kind<IO, Unit>> release) {
        return narrow(acquire).flatMap(r -> suspend(() -> narrow(use.apply(r))).guarantee(release.apply(r)));
    }

    /** Starts this on a virtual thread; the fiber can be joined or cancelled. */
    public IO<Fiber<IO, A>> start() {
        return delay(() -> {
            FutureTask<A> task = new FutureTask<>(this::unsafeRunSync);
            Thread thread = Thread.ofVirtual().name("io-fiber").start(task);
            return new IOFiber<>(task, thread);
        });
    }

    /** Runs both at once; the first to succeed wins and the other is cancelled. */
    public static <A, B> IO<Either<A, B>> race(Kind<IO, A> fa, Kind<IO, B> fb) {
        return delay(() -> {
            try (var scope = StructuredTaskScope.open(StructuredTaskScope.Joiner.<Either<A, B>>anySuccessfulOrThrow())) {
                scope.fork(() -> Either.<A, B>left(narrow(fa).unsafeRunSync()));
                scope.fork(() -> Either.<A, B>right(narrow(fb).unsafeRunSync()));
                return joinOrRethrow(scope);
            }
        });
    }

    /** Runs both at once and combines the results; a failure cancels the other. */
    public static <A, B, C> IO<C> parMap2(Kind<IO, A> fa, Kind<IO, B> fb, BiFunction<? super A, ? super B, ? extends C> f) {
        return delay(() -> {
            try (var scope = StructuredTaskScope.open()) {
                var a = scope.fork(narrow(fa)::unsafeRunSync);
                var b = scope.fork(narrow(fb)::unsafeRunSync);
                joinOrRethrow(scope);
                return f.apply(a.get(), b.get());
            }
        });
    }

    private static <R> R joinOrRethrow(StructuredTaskScope<?, R, ExecutionException> scope) throws Exception {
        try {
            return scope.join();
        } catch (ExecutionException e) {
            throw e.getCause() instanceof Exception cause ? cause : e;
        }
    }

    // ------------------------------------------------------------------- running

    /** Runs this IO on the calling thread and returns its result, or throws its error. */
    @SuppressWarnings("unchecked")
    public final A unsafeRunSync() throws Exception {
        Deque<Frame> stack = new ArrayDeque<>();
        IO<?> current = this;
        while (true) {
            if (Thread.currentThread().isInterrupted() && !(current instanceof RaiseError)) {
                current = new RaiseError<>(new InterruptedException("IO cancelled"));
            }
            switch (current) {
                case Pure<?> p -> {
                    Frame next = popUntil(stack, Bind.class);
                    if (next == null) {
                        return (A) p.value;
                    }
                    current = step(() -> narrow((Kind<IO, Object>) ((Bind) next).f.apply(p.value)));
                }
                case Delay<?> d -> current = step(() -> pure(d.thunk.call()));
                case Suspend<?> s -> current = step(() -> s.thunk.call());
                case FlatMap<?, ?> fm -> {
                    stack.push(new Bind((Function<Object, Kind<IO, Object>>) fm.f));
                    current = fm.source;
                }
                case HandleErrorWith<?> h -> {
                    stack.push(new Handler((Function<Throwable, Kind<IO, Object>>) h.handler));
                    current = h.source;
                }
                case RaiseError<?> r -> {
                    Frame handler = popUntil(stack, Handler.class);
                    if (handler == null) {
                        throw r.error instanceof Exception e ? e : new ExecutionException(r.error);
                    }
                    if (r.error instanceof InterruptedException) {
                        Thread.interrupted(); // the handler decides what happens next
                    }
                    current = step(() -> narrow(((Handler) handler).f.apply(r.error)));
                }
                case Async<?> a -> current = awaitCallback((Async<Object>) a);
            }
        }
    }

    private static IO<?> step(Callable<? extends IO<?>> next) {
        try {
            return next.call();
        } catch (Throwable e) {
            return new RaiseError<>(e);
        }
    }

    private static IO<?> awaitCallback(Async<Object> a) {
        var result = new CompletableFuture<Either<Throwable, Object>>();
        try {
            a.register.accept(result::complete);
            return result.get().fold(RaiseError::new, Pure::new);
        } catch (InterruptedException e) {
            return new RaiseError<>(e);
        } catch (Throwable e) {
            return new RaiseError<>(e instanceof ExecutionException ee && ee.getCause() != null ? ee.getCause() : e);
        }
    }

    /** Pops frames until one of {@code kind}, discarding the others; null if none is left. */
    private static Frame popUntil(Deque<Frame> stack, Class<? extends Frame> kind) {
        while (!stack.isEmpty()) {
            Frame f = stack.pop();
            if (kind.isInstance(f)) {
                return f;
            }
        }
        return null;
    }

    private sealed interface Frame {}

    private record Bind(Function<Object, Kind<IO, Object>> f) implements Frame {}

    private record Handler(Function<Throwable, Kind<IO, Object>> f) implements Frame {}

    // ---------------------------------------------------------------------- nodes

    private static final class Pure<A> extends IO<A> {
        final A value;

        Pure(A value) {
            this.value = value;
        }
    }

    private static final class Delay<A> extends IO<A> {
        final Callable<? extends A> thunk;

        Delay(Callable<? extends A> thunk) {
            this.thunk = thunk;
        }
    }

    private static final class Suspend<A> extends IO<A> {
        final Callable<? extends IO<A>> thunk;

        Suspend(Callable<? extends IO<A>> thunk) {
            this.thunk = thunk;
        }
    }

    private static final class FlatMap<S, A> extends IO<A> {
        final IO<S> source;
        final Function<? super S, ? extends Kind<IO, A>> f;

        FlatMap(IO<S> source, Function<? super S, ? extends Kind<IO, A>> f) {
            this.source = source;
            this.f = f;
        }
    }

    private static final class HandleErrorWith<A> extends IO<A> {
        final IO<A> source;
        final Function<? super Throwable, ? extends Kind<IO, A>> handler;

        HandleErrorWith(IO<A> source, Function<? super Throwable, ? extends Kind<IO, A>> handler) {
            this.source = source;
            this.handler = handler;
        }
    }

    private static final class RaiseError<A> extends IO<A> {
        final Throwable error;

        RaiseError(Throwable error) {
            this.error = error;
        }
    }

    private static final class Async<A> extends IO<A> {
        final Consumer<Consumer<Either<Throwable, A>>> register;

        Async(Consumer<Consumer<Either<Throwable, A>>> register) {
            this.register = register;
        }
    }

    private record IOFiber<A>(FutureTask<A> task, Thread thread) implements Fiber<IO, A> {
        @Override
        public Kind<IO, A> join() {
            return suspend(() -> {
                try {
                    return pure(task.get());
                } catch (ExecutionException e) {
                    return raiseError(e.getCause());
                } catch (java.util.concurrent.CancellationException e) {
                    return raiseError(new InterruptedException("fiber cancelled"));
                }
            });
        }

        @Override
        public Kind<IO, Unit> cancel() {
            return delay(() -> {
                thread.interrupt();
                thread.join();
                return Unit.UNIT;
            });
        }
    }

    // ------------------------------------------------------------------ instance

    /** IO's instance for the whole ladder: Functor through Concurrent. */
    @Given
    public static final IOConcurrent concurrent = new IOConcurrent();

    public static final class IOConcurrent implements Concurrent<IO> {
        private IOConcurrent() {}

        @Override
        public <A> Kind<IO, A> pure(A a) {
            return IO.pure(a);
        }

        @Override
        public <A, B> Kind<IO, B> flatMap(Kind<IO, A> fa, Function<? super A, ? extends Kind<IO, B>> f) {
            return narrow(fa).flatMap(f);
        }

        @Override
        public <A> Kind<IO, A> raiseError(Throwable e) {
            return IO.raiseError(e);
        }

        @Override
        public <A> Kind<IO, A> handleErrorWith(Kind<IO, A> fa, Function<? super Throwable, ? extends Kind<IO, A>> handler) {
            return narrow(fa).handleErrorWith(handler);
        }

        @Override
        public <A, B> Kind<IO, B> bracket(Kind<IO, A> acquire, Function<? super A, ? extends Kind<IO, B>> use,
                                          Function<? super A, ? extends Kind<IO, Unit>> release) {
            return IO.bracket(acquire, use, release);
        }

        @Override
        public <A> Kind<IO, A> suspend(Callable<? extends Kind<IO, A>> thunk) {
            return IO.suspend(() -> narrow(thunk.call()));
        }

        @Override
        public <A> Kind<IO, A> delay(Callable<? extends A> thunk) {
            return IO.delay(thunk);
        }

        @Override
        public <A> Kind<IO, A> async(Consumer<Consumer<Either<Throwable, A>>> register) {
            return IO.async(register);
        }

        @Override
        public <A> Kind<IO, Fiber<IO, A>> start(Kind<IO, A> fa) {
            return narrow(fa).start();
        }

        @Override
        public <A, B> Kind<IO, Either<A, B>> race(Kind<IO, A> fa, Kind<IO, B> fb) {
            return IO.race(fa, fb);
        }

        @Override
        public <A, B, C> Kind<IO, C> parMap2(Kind<IO, A> fa, Kind<IO, B> fb, BiFunction<? super A, ? super B, ? extends C> f) {
            return IO.parMap2(fa, fb, f);
        }
    }
}
