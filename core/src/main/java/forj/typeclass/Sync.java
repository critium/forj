package forj.typeclass;

import forj.Kind;
import java.util.concurrent.Callable;

/** Can suspend side effects: {@code delay(() -> println(...))} describes the print, doesn't do it. */
public interface Sync<F> extends Bracket<F, Throwable> {

    /** Suspends a computation that produces an {@code F}. */
    <A> Kind<F, A> suspend(Callable<? extends Kind<F, A>> thunk);

    /** Suspends a side effect; exceptions become failed {@code F}s. */
    default <A> Kind<F, A> delay(Callable<? extends A> thunk) {
        return suspend(() -> pure(thunk.call()));
    }
}
