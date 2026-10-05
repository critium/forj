package forj.examples.typeclasses;

/** A data type that brings its own instance: found without any import. */
public record Money(long cents) {
    given Show<Money> show = m -> String.format("$%d.%02d", m.cents() / 100, m.cents() % 100);
}
