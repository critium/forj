package forj.typeclass;

import forj.Kind;
import forj.data.Unit;
import java.util.function.Function;

/** A {@link MonadError} that can guarantee clean-up: acquire, use, release whatever happens. */
public interface Bracket<F, E> extends MonadError<F, E> {

    <A, B> Kind<F, B> bracket(Kind<F, A> acquire, Function<? super A, ? extends Kind<F, B>> use,
                              Function<? super A, ? extends Kind<F, Unit>> release);

    /** Runs {@code finalizer} after {@code fa}, whether it succeeds or fails. */
    default <A> Kind<F, A> guarantee(Kind<F, A> fa, Kind<F, Unit> finalizer) {
        return bracket(unit(), u -> fa, u -> finalizer);
    }
}
