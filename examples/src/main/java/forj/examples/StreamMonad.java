package forj.examples;

import forj.Kind;
import forj.Lower;
import forj.typeclass.Monad;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Adds {@code Stream} to forj, as a library would for its own type:
 * {@code import static forj.examples.StreamMonad.*;} is all a caller needs.
 *
 * <ul>
 *   <li>a wrapper making a {@code Stream<A>} a {@code Kind<Stream, A>},</li>
 *   <li>{@code forjLift} and a {@code @Lower} method to go in and out of {@code Kind},</li>
 *   <li>a {@code given Monad<Stream>}.</li>
 * </ul>
 */
@SuppressWarnings("rawtypes")
public final class StreamMonad {
    private StreamMonad() {}

    record StreamK<A>(Stream<A> stream) implements Kind<Stream, A> {}

    public static <A> Kind<Stream, A> forjLift(Stream<A> stream) {
        return new StreamK<>(stream);
    }

    @Lower
    public static <A> Stream<A> lowerStream(Kind<Stream, A> kind) {
        return ((StreamK<A>) kind).stream();
    }

    given Monad<Stream> monad = new Monad<>() {
        @Override
        public <A> Kind<Stream, A> pure(A a) {
            return new StreamK<>(Stream.of(a));
        }

        @Override
        public <A, B> Kind<Stream, B> flatMap(Kind<Stream, A> fa, Function<? super A, ? extends Kind<Stream, B>> f) {
            return new StreamK<>(lowerStream(fa).flatMap(a -> lowerStream(f.apply(a))));
        }
    };
}
