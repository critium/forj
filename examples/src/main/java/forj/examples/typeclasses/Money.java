package forj.examples.typeclasses;

import forj.typeclass.Monoid;

/** A data type that brings its own instance: found without any import. */
public record Money(long cents) {
    given Show<Money> show = m -> f"$$${m.cents() / 100}%d.${m.cents() % 100}%02d";   // $$ is a literal $
    given Monoid<Money> sum = Monoid.of(new Money(0), (a, b) -> new Money(a.cents() + b.cents()));
}
