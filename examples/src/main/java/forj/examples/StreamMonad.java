package forj.examples;

import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * A monad instance living outside forj, as a third-party library would ship one:
 * {@code import static forj.examples.StreamMonad.*;} is all a caller needs.
 */
public final class StreamMonad {
    private StreamMonad() {}

    public static <A, B> Stream<B> forjFlatMap(Stream<A> m, Function<? super A, ? extends Stream<B>> f) {
        return m.flatMap(f);
    }

    public static <A, B> Stream<B> forjMap(Stream<A> m, Function<? super A, ? extends B> f) {
        return m.map(f);
    }

    public static <A> Stream<A> forjFilter(Stream<A> m, Predicate<? super A> p) {
        return m.filter(p);
    }
}
