package forj.examples.typeclasses;

/** Givens meant to be imported: {@code import static forj.examples.typeclasses.Euro.*;} */
public final class Euro {
    private Euro() {}

    given Show<Money> euros = m -> String.format("€%d,%02d", m.cents() / 100, m.cents() % 100);
}
