package forj;

import forj.data.CallableK;
import forj.data.ListK;
import forj.data.OptionalK;
import forj.typeclass.Functor;
import forj.typeclass.FunctorFilter;
import forj.typeclass.Monad;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * What forj comprehensions compile to. The plugin rewrites
 *
 * <pre>{@code
 * forj {                       forj.For.run(() -> forjLower(
 *     x <- xs;                     forj.For.forjFlatMap(forjLift(xs), x ->
 *     guard(p(x));      ==>            forj.For.forjMap(forj.For.forjFilter(forjLift(ys(x)), y -> p(x)),
 *     y <- ys(x);                          y -> f(x, y)))))
 * } yield f(x, y);
 * }</pre>
 *
 * (guards filter the generator they follow) and passes each combinator its type class
 * instance at compile time: a comprehension over {@code F} needs a {@code Monad<F>}
 * ({@code Functor<F>} for a single generator, plus {@code FunctorFilter<F>} for guards).
 * Generic code ({@code <F> ... using Monad<F> m}) works the same way as concrete types.
 *
 * <h2>Lifting</h2>
 * {@code forjLift} turns a generator's value into a {@code Kind} and is called unqualified,
 * so overloads from any {@code import static} take part. {@code forjLower} turns the result
 * back, through the {@link Lower} method for the witness. A library adds a type by shipping a
 * {@code forjLift} overload, a {@code @Lower} method and a {@code given Monad<ItsType>}.
 * Types that already are a {@code Kind} (like {@code IO}) need none of the first two.
 * This class is imported automatically into files that use forj.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class For {
    private For() {}

    /** Runs the desugared body; target of the rewritten {@code forj}. */
    public static <R> R run(Supplier<R> body) {
        return body.get();
    }

    // ------------------------------------------------------------- combinators

    public static <F, A, B> Kind<F, B> forjFlatMap(Kind<F, A> fa, Function<? super A, ? extends Kind<F, B>> f,
                                                   @Using Monad<F> monad) {
        return monad.flatMap(fa, f);
    }

    public static <F, A, B> Kind<F, B> forjMap(Kind<F, A> fa, Function<? super A, ? extends B> f,
                                               @Using Functor<F> functor) {
        return functor.map(fa, f);
    }

    public static <F, A> Kind<F, A> forjFilter(Kind<F, A> fa, Predicate<? super A> p,
                                               @Using FunctorFilter<F> filter) {
        return filter.filter(fa, p);
    }

    // ------------------------------------------------------------------ lifting

    public static <F, A> Kind<F, A> forjLift(Kind<F, A> fa) {
        return fa;
    }

    /** {@code forjLift} for values that are already a {@code Kind}; the plugin picks it when overloads would be ambiguous. */
    public static <F, A> Kind<F, A> forjKind(Kind<F, A> fa) {
        return fa;
    }

    public static <A> Kind<List, A> forjLift(List<A> list) {
        return new ListK<>(list);
    }

    public static <A> Kind<Optional, A> forjLift(Optional<A> optional) {
        return new OptionalK<>(optional);
    }

    public static <A> Kind<Callable, A> forjLift(Callable<A> callable) {
        return new CallableK<>(callable);
    }

    /**
     * Turns a comprehension's result back into a plain type. Written for types that are their
     * own {@code Kind} (like {@code IO}) and for generic {@code F}: the target type must be a
     * {@code Kind<F, A>}, so a wrong yield type is a compile error. For witnesses with an
     * {@link Lower} method the plugin calls that instead, e.g. {@link #lowerList}.
     */
    public static <F, A, K extends Kind<F, A>> K forjLower(Kind<F, A> fa) {
        return (K) fa;
    }

    /**
     * What the plugin wraps a call returning {@code Kind<F, A>} in when it is assigned or
     * returned: {@code IO<Unit> app = checkout(cart);} works without {@code IO.narrow}. Checked:
     * the target must be a {@code Kind<F, A>}.
     */
    public static <F, A, K extends Kind<F, A>> K forjNarrow(Kind<F, A> fa) {
        return (K) fa;
    }

    @Lower
    public static <A> List<A> lowerList(Kind<List, A> fa) {
        return ListK.narrow(fa);
    }

    @Lower
    public static <A> Optional<A> lowerOptional(Kind<Optional, A> fa) {
        return OptionalK.narrow(fa);
    }

    @Lower
    public static <A> Callable<A> lowerCallable(Kind<Callable, A> fa) {
        return CallableK.narrow(fa);
    }
}
