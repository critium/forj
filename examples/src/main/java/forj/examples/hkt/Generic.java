package forj.examples.hkt;

import forj.Kind;
import forj.typeclass.Monad;

/** One comprehension, written once, for any monad. */
public final class Generic {
    private Generic() {}

    /** Every way of adding an {@code a} to a {@code b}, in whatever {@code F} means. */
    public static <F> Kind<F, Integer> addBoth(Kind<F, Integer> a, Kind<F, Integer> b) using Monad<F> m {
        return forj {
            x <- a;
            y <- b;
        } yield x + y;
    }
}
