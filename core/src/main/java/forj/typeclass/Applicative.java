package forj.typeclass;

import forj.Kind;
import java.util.function.BiFunction;

/** A {@link Functor} that can lift plain values and combine independent {@code F}s. */
public interface Applicative<F> extends Functor<F> {

    <A> Kind<F, A> pure(A a);

    <A, B, C> Kind<F, C> map2(Kind<F, A> fa, Kind<F, B> fb, BiFunction<? super A, ? super B, ? extends C> f);

    default Kind<F, forj.data.Unit> unit() {
        return pure(forj.data.Unit.UNIT);
    }
}
