package forj.examples.typeclasses;

import java.util.List;
import java.util.Optional;

/** Uses Show without passing a single instance by hand. */
public final class Report {
    private Report() {}

    /** Generic code asks for an instance in turn, and passes it on implicitly. */
    static <A> String line(String label, A value)(using Show<A> show) {
        return label + ": " + Show.show(value);
    }

    public static List<String> lines() {
        return List.of(
                line("count", 3),
                line("names", List.of("ana", "bo")),
                line("balance", new Money(123_45)),
                line("history", List.of(Optional.of(new Money(5_00)), Optional.<Money>empty())));
    }
}
