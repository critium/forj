package forj.typeclass;

import forj.Kind;
import forj.data.Either;
import java.util.function.BiFunction;

/** Can run {@code F}s at the same time: start fibers, race them, run them in parallel. */
public interface Concurrent<F> extends Async<F> {

    /** Starts {@code fa} running in the background (on a virtual thread). */
    <A> Kind<F, Fiber<F, A>> start(Kind<F, A> fa);

    /** Runs both; the first to succeed wins and the other is cancelled. */
    <A, B> Kind<F, Either<A, B>> race(Kind<F, A> fa, Kind<F, B> fb);

    /** Runs both at the same time and combines the results; a failure cancels the other. */
    <A, B, C> Kind<F, C> parMap2(Kind<F, A> fa, Kind<F, B> fb, BiFunction<? super A, ? super B, ? extends C> f);
}
