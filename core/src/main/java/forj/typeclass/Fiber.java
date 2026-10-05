package forj.typeclass;

import forj.Kind;
import forj.data.Unit;

/** A running {@code F} started with {@link Concurrent#start}: wait for it or cancel it. */
public interface Fiber<F, A> {

    /** Waits for the result; fails if the fiber failed or was cancelled. */
    Kind<F, A> join();

    /** Interrupts the fiber and waits for it to stop. */
    Kind<F, Unit> cancel();
}
