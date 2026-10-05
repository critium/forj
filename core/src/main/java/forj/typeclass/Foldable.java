package forj.typeclass;

import forj.Kind;
import java.util.function.BiFunction;

/** Can reduce the values inside an {@code F} to one value, left to right. */
public interface Foldable<F> {

    <A, B> B foldLeft(Kind<F, A> fa, B initial, BiFunction<? super B, ? super A, ? extends B> f);

    /** Combines every value with the monoid; {@code empty()} when there are none. */
    default <A> A combineAll(Kind<F, A> fa, Monoid<A> monoid) {
        return foldLeft(fa, monoid.empty(), monoid::combine);
    }
}
