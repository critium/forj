package forj.examples.typeclasses;

/** Givens meant to be imported: {@code import static forj.examples.typeclasses.Euro.*;} */
public final class Euro {
    private Euro() {}

    given Show<Money> euros = m -> f"€${m.cents() / 100}%d,${m.cents() % 100}%02d";
}
