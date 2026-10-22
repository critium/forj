package forj.typeclass;

import forj.Kind;

/**
 * An {@link Async} effect that can also be run: the top of the ladder for code that is handed
 * an {@code F} and has to produce a value, such as a server running a request's
 * {@code F<Response>} on that request's virtual thread.
 *
 * <p>Like cats-effect 2's {@code Effect}. Running is "unsafe" because it performs the
 * effects: call it once, at the edge of the program, not inside other {@code F}s.
 *
 * <p><b>Why there is no {@code runAsync} or {@code runCancelable}.</b> cats-effect 2 needed
 * them because blocking a thread was expensive: its {@code IO} ran on a small pool, and on
 * Scala.js there is only one thread, so running had to hand back a callback instead of
 * waiting. Here {@code F}s run on virtual threads, where blocking just parks the virtual
 * thread and frees its carrier, so waiting is the cheap way to run. The other two follow:
 * <ul>
 *   <li><b>run asynchronously:</b> run it on a virtual thread of its own,
 *       {@code Thread.ofVirtual().start(() -> effect.unsafeRunSync(fa))};</li>
 *   <li><b>run cancelably:</b> interrupt that thread. {@code IO} checks for interruption
 *       between steps and at blocking calls, and runs its finalizers (as
 *       {@code Fiber.cancel()} does inside {@code F}).</li>
 * </ul>
 */
public interface Effect<F> extends Async<F> {

    /** Runs {@code fa} on the calling thread, blocking until it completes, and returns its result or throws its error. */
    <A> A unsafeRunSync(Kind<F, A> fa) throws Exception;

    /**
     * {@link #unsafeRunSync} under a plainer name, for code that reads better without the
     * prefix. Same method, same caveat: it performs the effects, so call it at the edge.
     */
    default <A> A runSync(Kind<F, A> fa) throws Exception {
        return unsafeRunSync(fa);
    }
}
