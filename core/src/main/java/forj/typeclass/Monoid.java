package forj.typeclass;

import java.util.function.BinaryOperator;

/** A {@link Semigroup} with an identity: {@code combine(empty(), a)} is {@code a}. */
public interface Monoid<A> extends Semigroup<A> {

    A empty();

    /** A monoid from its identity and its combine function. */
    static <A> Monoid<A> of(A empty, BinaryOperator<A> combine) {
        return new Monoid<>() {
            @Override
            public A empty() {
                return empty;
            }

            @Override
            public A combine(A x, A y) {
                return combine.apply(x, y);
            }
        };
    }

    /** Combines all values in order; {@code empty()} for none. */
    default A combineAll(Iterable<? extends A> as) {
        A acc = empty();
        for (A a : as) {
            acc = combine(acc, a);
        }
        return acc;
    }
}
