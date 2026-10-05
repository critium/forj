package forj.examples.typeclasses;

import static forj.examples.typeclasses.Euro.*;

import java.util.List;

/** An imported given beats the one next to the data type. */
public final class ImportedGivens {
    private ImportedGivens() {}

    public static List<String> report() {
        return List.of(
                Show.show(new Money(1999)),                            // Euro.euros, not Money.show
                Show.show(List.of(new Money(5))));                     // Show.list(Euro.euros)
    }
}
