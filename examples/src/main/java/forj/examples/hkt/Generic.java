package forj.examples.hkt;

import forj.typeclass.Applicative;
import forj.typeclass.Monad;
import forj.typeclass.Monoid;
import forj.typeclass.Semigroup;
import forj.typeclass.Traverse;

/**
 * The same idea, "add a and b", made more generic one step at a time. Each step asks for
 * less, so it works for more types.
 */
public final class Generic {
    private Generic() {}

    /** One comprehension, written once, for any monad. */
    public static <F<_>> F<Integer> addBoth(F<Integer> a, F<Integer> b)(using Monad<F> m) {
        return forj {
            x <- a;
            y <- b;
        } yield x + y;
    }

    /**
     * {@code b} doesn't depend on {@code a}'s value, so {@code Applicative} is enough: works
     * for every effect {@code addBoth} does, plus ones that have no lawful {@code Monad}.
     */
    public static <F<_>> F<Integer> addIndependent(F<Integer> a, F<Integer> b)(using Applicative<F> ap) {
        return ap.map2(a, b, Integer::sum);
    }

    /** Generic in the value too: "add" is whatever {@code Semigroup<A>} the call site resolves. */
    public static <F<_>, A> F<A> combineBoth(F<A> a, F<A> b)(using Applicative<F> ap, Semigroup<A> s) {
        return ap.map2(a, b, s::combine);
    }

    /**
     * Generic in how many: runs every {@code F} in any traversable {@code G} (a list, an
     * optional, ...) and combines the results, {@code empty()} when there are none.
     */
    public static <G<_>, F<_>, A> F<A> combineAll(G<? extends F<A>> fas)(using Traverse<G> t, Applicative<F> ap, Monoid<A> m) {
        // `? extends` lets callers pass a List<IO<Integer>>, not only a List<Kind<IO, Integer>>
        return ap.map(t.sequence(ap, fas), as -> t.combineAll(as, m));
    }
}
