package forj.typeclass;

import forj.Kind;
import forj.data.Either;
import java.util.function.Consumer;

/** Can wait for a callback-based computation, such as a client library's completion handler. */
public interface Async<F> extends Sync<F> {

    /** Runs {@code register}, then completes with whatever it passes to the callback (once). */
    <A> Kind<F, A> async(Consumer<Consumer<Either<Throwable, A>>> register);

    /** Never completes (until cancelled). */
    default <A> Kind<F, A> never() {
        return async(callback -> {});
    }
}
