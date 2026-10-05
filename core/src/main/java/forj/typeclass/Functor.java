package forj.typeclass;

import forj.Kind;
import forj.data.Unit;
import java.util.function.Function;

/** Can transform the values inside an {@code F}: the bottom of the capability ladder. */
public interface Functor<F> {

    <A, B> Kind<F, B> map(Kind<F, A> fa, Function<? super A, ? extends B> f);

    default <A, B> Kind<F, B> as(Kind<F, A> fa, B b) {
        return map(fa, a -> b);
    }

    default <A> Kind<F, Unit> voided(Kind<F, A> fa) {
        return as(fa, Unit.UNIT);
    }
}
