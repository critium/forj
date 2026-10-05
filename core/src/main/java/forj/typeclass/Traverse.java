package forj.typeclass;

import forj.Kind;
import java.util.function.Function;

/** A {@link Functor} whose values can be visited with an effect, collecting the results. */
public interface Traverse<F> extends Functor<F> {

    <G, A, B> Kind<G, Kind<F, B>> traverse(Applicative<G> g, Kind<F, A> fa, Function<? super A, ? extends Kind<G, B>> f);

    default <G, A> Kind<G, Kind<F, A>> sequence(Applicative<G> g, Kind<F, Kind<G, A>> fga) {
        return traverse(g, fga, ga -> ga);
    }
}
