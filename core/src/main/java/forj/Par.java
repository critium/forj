package forj;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Subtask;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Parallel combinators for {@link Callable}, in the spirit of cats-effect's {@code parMapN}
 * and {@code parTraverse}.
 *
 * <p>Like everything over {@code Callable} in forj, these are lazy: they return a
 * {@code Callable} and nothing runs until {@code call()}. Each call opens one
 * {@link StructuredTaskScope}, forks every task on its own virtual thread and joins them.
 * If any task fails, the others are cancelled (interrupted) and {@code call()} throws that
 * task's exception.
 *
 * <pre>{@code
 * Callable<String> dashboard = forj {
 *     parts <- Par.mapN(get("/profile"), get("/orders"), get("/recommendations"), Parts::new);
 *     shipping <- get("/shipping/" + itemCount(parts.orders()));   // runs after all three
 * } yield render(parts, shipping);
 * }</pre>
 *
 * <p>Uses {@code StructuredTaskScope}, a preview API in JDK 27 and 28 (forj builds with
 * {@code --enable-preview} throughout).
 */
public final class Par {
    private Par() {}

    @FunctionalInterface
    public interface Function3<A, B, C, R> {
        R apply(A a, B b, C c);
    }

    @FunctionalInterface
    public interface Function4<A, B, C, D, R> {
        R apply(A a, B b, C c, D d);
    }

    public static <A, B, R> Callable<R> mapN(
            Callable<? extends A> a, Callable<? extends B> b,
            BiFunction<? super A, ? super B, ? extends R> f) {
        return () -> {
            try (var scope = StructuredTaskScope.open()) {
                Subtask<? extends A> ra = scope.fork(a);
                Subtask<? extends B> rb = scope.fork(b);
                join(scope);
                return f.apply(ra.get(), rb.get());
            }
        };
    }

    public static <A, B, C, R> Callable<R> mapN(
            Callable<? extends A> a, Callable<? extends B> b, Callable<? extends C> c,
            Function3<? super A, ? super B, ? super C, ? extends R> f) {
        return () -> {
            try (var scope = StructuredTaskScope.open()) {
                Subtask<? extends A> ra = scope.fork(a);
                Subtask<? extends B> rb = scope.fork(b);
                Subtask<? extends C> rc = scope.fork(c);
                join(scope);
                return f.apply(ra.get(), rb.get(), rc.get());
            }
        };
    }

    public static <A, B, C, D, R> Callable<R> mapN(
            Callable<? extends A> a, Callable<? extends B> b, Callable<? extends C> c, Callable<? extends D> d,
            Function4<? super A, ? super B, ? super C, ? super D, ? extends R> f) {
        return () -> {
            try (var scope = StructuredTaskScope.open()) {
                Subtask<? extends A> ra = scope.fork(a);
                Subtask<? extends B> rb = scope.fork(b);
                Subtask<? extends C> rc = scope.fork(c);
                Subtask<? extends D> rd = scope.fork(d);
                join(scope);
                return f.apply(ra.get(), rb.get(), rc.get(), rd.get());
            }
        };
    }

    /** Runs {@code f} on every element in parallel; results keep the input order. */
    public static <A, B> Callable<List<B>> traverse(List<? extends A> as, Function<? super A, ? extends Callable<? extends B>> f) {
        return () -> {
            try (var scope = StructuredTaskScope.open()) {
                List<Subtask<? extends B>> forked = new ArrayList<>(as.size());
                for (A a : as) {
                    forked.add(scope.fork(f.apply(a)));
                }
                join(scope);
                List<B> out = new ArrayList<>(forked.size());
                for (Subtask<? extends B> s : forked) {
                    out.add(s.get());
                }
                return Collections.unmodifiableList(out);
            }
        };
    }

    /** Runs every task in parallel; results keep the input order. */
    public static <A> Callable<List<A>> sequence(List<? extends Callable<? extends A>> tasks) {
        return traverse(tasks, task -> task);
    }

    /** Waits for all subtasks; on failure rethrows the failed task's own exception. */
    private static void join(StructuredTaskScope<?, ?, ExecutionException> scope) throws Exception {
        try {
            scope.join();
        } catch (ExecutionException e) {
            throw e.getCause() instanceof Exception cause ? cause : e;
        }
    }
}
