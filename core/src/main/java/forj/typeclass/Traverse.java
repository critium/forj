package forj.typeclass;

import forj.Kind;
import java.util.function.Function;

/** A {@link Functor} and {@link Foldable} whose values can be visited with an effect, collecting the results. */
public interface Traverse<F> extends Functor<F>, Foldable<F> {

    <G, A, B> Kind<G, Kind<F, B>> traverse(Applicative<G> g, Kind<F, A> fa, Function<? super A, ? extends Kind<G, B>> f);

    /** Turns an {@code F} of {@code G}s inside out; any subtype of {@code Kind<G, A>} will do (e.g. an {@code IO<A>}). */
    default <G, A> Kind<G, Kind<F, A>> sequence(Applicative<G> g, Kind<F, ? extends Kind<G, A>> fga) {
        return traverse(g, fga, ga -> ga);
    }
}
