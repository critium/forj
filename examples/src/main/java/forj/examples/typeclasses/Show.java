package forj.examples.typeclasses;

import java.util.List;
import java.util.Optional;

/** A type class: how to render an {@code A} as text. */
public interface Show<A> {
    String show(A a);

    // Instances for JDK types live with the type class, so they're always found.
    given Show<Integer> integer = i -> Integer.toString(i);
    given Show<String> string = s -> '"' + s + '"';

    given <A> Show<List<A>> list(using Show<A> element) {
        return xs -> xs.stream().map(element::show).toList().toString();
    }

    given <A> Show<Optional<A>> optional(using Show<A> value) {
        return o -> o.map(a -> "Some(" + value.show(a) + ")").orElse("None");
    }

    /** {@code x.show()} for any {@code x} with a {@code Show}; also callable as {@code Show.show(x)}. */
    extension <A> String show(A a)(using Show<A> s) {
        return s.show(a);
    }
}
