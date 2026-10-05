package forj.typeclass;

import forj.Kind;
import java.util.function.Predicate;

/** A {@link Functor} that can drop values: what {@code guard(...)} in a comprehension needs. */
public interface FunctorFilter<F> extends Functor<F> {

    <A> Kind<F, A> filter(Kind<F, A> fa, Predicate<? super A> p);
}
