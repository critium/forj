package forj.data;

import forj.Kind;
import java.util.concurrent.Callable;

/** A {@code Callable<A>} as a {@code Kind<Callable, A>}. */
@SuppressWarnings("rawtypes")
public record CallableK<A>(Callable<A> callable) implements Kind<Callable, A> {

    public static <A> Callable<A> narrow(Kind<Callable, A> kind) {
        return ((CallableK<A>) kind).callable();
    }
}
