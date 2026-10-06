package forj;

import forj.data.Either;
import forj.data.Unit;
import forj.typeclass.ApplicativeError;
import forj.typeclass.Applicative;
import forj.typeclass.Bracket;
import forj.typeclass.Concurrent;
import forj.typeclass.Fiber;
import forj.typeclass.Foldable;
import forj.typeclass.Functor;
import forj.typeclass.FunctorFilter;
import forj.typeclass.Monad;
import forj.typeclass.Monoid;
import forj.typeclass.Semigroup;
import forj.typeclass.Traverse;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Extension methods for the type classes, like cats' syntax imports: {@code fa.map(f)} on any
 * {@code F<A>} with a {@code Functor<F>}, {@code a.combine(b)} on any {@code A} with a
 * {@code Semigroup<A>}. The forj plugin always searches here last, so they need no import.
 * Methods a type has itself win: {@code io.map(f)} calls {@code IO.map}.
 *
 * <p>Error-handling syntax fixes the error type to {@code Throwable}, as {@code Sync} does.
 */
@SuppressWarnings("rawtypes")
public final class Syntax {
    private Syntax() {}

    // ------------------------------------------------------------------- Functor

    @Extension
    public static <F, A, B> Kind<F, B> map(Kind<F, A> fa, Function<? super A, ? extends B> f, @Using Functor<F> functor) {
        return functor.map(fa, f);
    }

    @Extension
    public static <F, A, B> Kind<F, B> as(Kind<F, A> fa, B b, @Using Functor<F> functor) {
        return functor.as(fa, b);
    }

    @Extension
    public static <F, A> Kind<F, Unit> voided(Kind<F, A> fa, @Using Functor<F> functor) {
        return functor.voided(fa);
    }

    // --------------------------------------------------------------- Applicative

    @Extension
    public static <F, A, B, C> Kind<F, C> map2(Kind<F, A> fa, Kind<F, B> fb,
                                              BiFunction<? super A, ? super B, ? extends C> f,
                                              @Using Applicative<F> applicative) {
        return applicative.map2(fa, fb, f);
    }

    // --------------------------------------------------------------------- Monad

    @Extension
    public static <F, A, B> Kind<F, B> flatMap(Kind<F, A> fa, Function<? super A, ? extends Kind<F, B>> f,
                                               @Using Monad<F> monad) {
        return monad.flatMap(fa, f);
    }

    /** Runs {@code fa}, then {@code fb}, keeping {@code fb}'s result: cats' {@code >>}. */
    @Extension
    public static <F, A, B> Kind<F, B> productR(Kind<F, A> fa, Kind<F, B> fb, @Using Monad<F> monad) {
        return monad.productR(fa, fb);
    }

    @Extension
    public static <F, A> Kind<F, A> flatten(Kind<F, ? extends Kind<F, A>> ffa, @Using Monad<F> monad) {
        return monad.flatMap(ffa, fa -> fa);
    }

    // ------------------------------------------------------------- FunctorFilter

    @Extension
    public static <F, A> Kind<F, A> filter(Kind<F, A> fa, Predicate<? super A> p, @Using FunctorFilter<F> filter) {
        return filter.filter(fa, p);
    }

    // ------------------------------------------------------ errors and resources

    @Extension
    public static <F, A> Kind<F, A> handleErrorWith(Kind<F, A> fa,
                                                    Function<? super Throwable, ? extends Kind<F, A>> handler,
                                                    @Using ApplicativeError<F, Throwable> errors) {
        return errors.handleErrorWith(fa, handler);
    }

    @Extension
    public static <F, A> Kind<F, A> handleError(Kind<F, A> fa, Function<? super Throwable, ? extends A> handler,
                                                @Using ApplicativeError<F, Throwable> errors) {
        return errors.handleError(fa, handler);
    }

    @Extension
    public static <F, A> Kind<F, Either<Throwable, A>> attempt(Kind<F, A> fa,
                                                               @Using ApplicativeError<F, Throwable> errors) {
        return errors.attempt(fa);
    }

    @Extension
    public static <F, A> Kind<F, A> guarantee(Kind<F, A> fa, Kind<F, Unit> finalizer,
                                              @Using Bracket<F, Throwable> bracket) {
        return bracket.guarantee(fa, finalizer);
    }

    // ---------------------------------------------------------------- Concurrent

    @Extension
    public static <F, A> Kind<F, Fiber<F, A>> start(Kind<F, A> fa, @Using Concurrent<F> concurrent) {
        return concurrent.start(fa);
    }

    @Extension
    public static <F, A, B> Kind<F, Either<A, B>> race(Kind<F, A> fa, Kind<F, B> fb, @Using Concurrent<F> concurrent) {
        return concurrent.race(fa, fb);
    }

    @Extension
    public static <F, A, B, C> Kind<F, C> parMap2(Kind<F, A> fa, Kind<F, B> fb,
                                                 BiFunction<? super A, ? super B, ? extends C> f,
                                                 @Using Concurrent<F> concurrent) {
        return concurrent.parMap2(fa, fb, f);
    }

    // ------------------------------------------------------- Foldable, Traverse

    @Extension
    public static <F, A, B> B foldLeft(Kind<F, A> fa, B initial, BiFunction<? super B, ? super A, ? extends B> f,
                                       @Using Foldable<F> foldable) {
        return foldable.foldLeft(fa, initial, f);
    }

    @Extension
    public static <F, A> A combineAll(Kind<F, A> fa, @Using Foldable<F> foldable, @Using Monoid<A> monoid) {
        return foldable.combineAll(fa, monoid);
    }

    @Extension
    public static <F, G, A> Kind<G, Kind<F, A>> sequence(Kind<F, ? extends Kind<G, A>> fga,
                                                         @Using Traverse<F> traverse, @Using Applicative<G> g) {
        return traverse.sequence(g, fga);
    }

    // ----------------------------------------------------------------- Semigroup

    @Extension
    public static <A> A combine(A x, A y, @Using Semigroup<A> semigroup) {
        return semigroup.combine(x, y);
    }
}
