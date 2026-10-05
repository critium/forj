package forj.examples.tagless;

import forj.data.Either;
import forj.typeclass.Concurrent;
import forj.typeclass.Functor;
import forj.typeclass.MonadError;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

/**
 * The capability ladder: each function asks for the least it needs, so it works with every
 * effect that has at least that much.
 *
 * <pre>
 * Functor → Applicative → Monad → ApplicativeError/MonadError → Bracket → Sync → Async → Concurrent
 * </pre>
 *
 * Callable is Sync: describe and retry work for it, withTimeout does not compile for it.
 * IO is Concurrent: all three work.
 */
public final class Ladder {
    private Ladder() {}

    /** Needs only to transform the result: Functor. */
    public static <F<_>> F<String> describe(F<Receipt> receipt)(using Functor<F> functor) {
        return functor.map(receipt, Receipt::summary);
    }

    /** Needs to run again after a failure: MonadError. */
    public static <F<_>, A> F<A> retry(F<A> attempt, int times)(using MonadError<F, Throwable> errors) {
        return times <= 1 ? attempt : errors.handleErrorWith(attempt, e -> retry(attempt, times - 1));
    }

    /** Needs to run two things at once and cancel the loser: Concurrent. */
    public static <F<_>, A> F<A> withTimeout(F<A> fa, Duration limit)(using Concurrent<F> concurrent) {
        F<String> timer = concurrent.delay(() -> {
            Thread.sleep(limit);
            return "timeout";
        });
        return forj {
            winner <- concurrent.race(fa, timer);
            result <- winner.fold(concurrent::pure, timedOut -> concurrent.<A>raiseError(new TimeoutException(s"after $limit")));
        } yield result;
    }
}
