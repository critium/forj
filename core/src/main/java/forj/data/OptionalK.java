package forj.data;

import forj.Kind;
import java.util.Optional;

/** An {@code Optional<A>} as a {@code Kind<Optional, A>}. */
@SuppressWarnings("rawtypes")
public record OptionalK<A>(Optional<A> optional) implements Kind<Optional, A> {

    public static <A> Optional<A> narrow(Kind<Optional, A> kind) {
        return ((OptionalK<A>) kind).optional();
    }
}
