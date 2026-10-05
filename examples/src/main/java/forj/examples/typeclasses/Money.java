package forj.examples.typeclasses;

/** A data type that brings its own instance: found without any import. */
public record Money(long cents) {
    given Show<Money> show = m -> f"$$${m.cents() / 100}%d.${m.cents() % 100}%02d";   // $$ is a literal $
}
