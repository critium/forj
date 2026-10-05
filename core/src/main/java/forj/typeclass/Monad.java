package forj.typeclass;

import forj.Kind;
import java.util.function.BiFunction;
import java.util.function.Function;

/** An {@link Applicative} whose next step can depend on the previous result: what forj comprehensions need. */
public interface Monad<F> extends Applicative<F> {

    <A, B> Kind<F, B> flatMap(Kind<F, A> fa, Function<? super A, ? extends Kind<F, B>> f);

    @Override
    default <A, B> Kind<F, B> map(Kind<F, A> fa, Function<? super A, ? extends B> f) {
        return flatMap(fa, a -> pure(f.apply(a)));
    }

    @Override
    default <A, B, C> Kind<F, C> map2(Kind<F, A> fa, Kind<F, B> fb, BiFunction<? super A, ? super B, ? extends C> f) {
        return flatMap(fa, a -> map(fb, b -> f.apply(a, b)));
    }

    default <A> Kind<F, A> flatten(Kind<F, Kind<F, A>> ffa) {
        return flatMap(ffa, fa -> fa);
    }

    /** Runs {@code fa}, then {@code fb}, keeping {@code fb}'s result. */
    default <A, B> Kind<F, B> productR(Kind<F, A> fa, Kind<F, B> fb) {
        return flatMap(fa, a -> fb);
    }
}
