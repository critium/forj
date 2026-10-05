package forj.data;

import forj.Kind;
import java.util.List;

/** A {@code List<A>} as a {@code Kind<List, A>}. */
@SuppressWarnings("rawtypes")
public record ListK<A>(List<A> list) implements Kind<List, A> {

    public static <A> List<A> narrow(Kind<List, A> kind) {
        return ((ListK<A>) kind).list();
    }
}
