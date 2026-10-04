package forj;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Scala-style for comprehensions for Java.
 *
 * <pre>{@code
 * List<String> r = forj {
 *     n <- List.of(1, 2, 3);
 *     guard(n % 2 == 1);
 *     s <- List.of("a", "b");
 * } yield s + n;
 * }</pre>
 *
 * The forj javac plugin ({@code -Xplugin:Forj}) rewrites every {@code forj} block into
 * nested {@code forjFlatMap}/{@code forjMap}/{@code forjFilter} calls before type checking,
 * and adds {@code import static forj.For.*;} to files that use it.
 *
 * <h2>Monad instances</h2>
 * A type {@code M<A>} participates in comprehensions when some statically imported
 * class provides these static overloads for it:
 * <pre>{@code
 * static <A, B> M<B> forjFlatMap(M<A> m, Function<? super A, ? extends M<B>> f)
 * static <A, B> M<B> forjMap(M<A> m, Function<? super A, ? extends B> f)
 * static <A>    M<A> forjFilter(M<A> m, Predicate<? super A> p)                // only needed for guard
 * }</pre>
 * The rewritten code calls these unqualified, so javac's overload resolution across
 * all {@code import static ...*} declarations picks the instance for the type at hand.
 * Third-party types plug in by shipping such a class; no plugin changes are needed.
 * This class holds the JDK instances.
 */
public final class For {
    private For() {}

    /** What the plugin turns {@code forj { ... } yield e} into: runs the desugared body. */
    public static <R> R run(Supplier<R> body) {
        return body.get();
    }

    // -------------------------------------------------------------- Optional

    public static <A, B> Optional<B> forjFlatMap(Optional<A> m, Function<? super A, ? extends Optional<B>> f) {
        return m.flatMap(f);
    }

    public static <A, B> Optional<B> forjMap(Optional<A> m, Function<? super A, ? extends B> f) {
        return m.map(f);
    }

    public static <A> Optional<A> forjFilter(Optional<A> m, Predicate<? super A> p) {
        return m.filter(p);
    }

    // ------------------------------------------------------------------ List

    public static <A, B> List<B> forjFlatMap(List<A> m, Function<? super A, ? extends List<B>> f) {
        List<B> out = new ArrayList<>();
        for (A a : m) {
            out.addAll(f.apply(a));
        }
        return Collections.unmodifiableList(out);
    }

    public static <A, B> List<B> forjMap(List<A> m, Function<? super A, ? extends B> f) {
        List<B> out = new ArrayList<>(m.size());
        for (A a : m) {
            out.add(f.apply(a));
        }
        return Collections.unmodifiableList(out);
    }

    public static <A> List<A> forjFilter(List<A> m, Predicate<? super A> p) {
        List<A> out = new ArrayList<>();
        for (A a : m) {
            if (p.test(a)) {
                out.add(a);
            }
        }
        return Collections.unmodifiableList(out);
    }

    // ---------------------------------------------------------------- Callable

    /*
     * A Callable is a lazy computation: a comprehension over Callables builds a bigger
     * Callable and runs nothing until call(). Steps run one after another on the calling
     * thread; blocking inside them is cheap on a virtual thread.
     */

    public static <A, B> Callable<B> forjFlatMap(Callable<A> m, Function<? super A, ? extends Callable<B>> f) {
        return () -> f.apply(m.call()).call();
    }

    public static <A, B> Callable<B> forjMap(Callable<A> m, Function<? super A, ? extends B> f) {
        return () -> f.apply(m.call());
    }

    /** A failed guard makes call() throw {@link NoSuchElementException}. */
    public static <A> Callable<A> forjFilter(Callable<A> m, Predicate<? super A> p) {
        return () -> {
            A a = m.call();
            if (!p.test(a)) {
                throw new NoSuchElementException("forj guard failed for " + a);
            }
            return a;
        };
    }
}
