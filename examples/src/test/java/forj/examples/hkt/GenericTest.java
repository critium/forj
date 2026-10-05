package forj.examples.hkt;

import static org.junit.jupiter.api.Assertions.assertEquals;

import forj.Kind;
import forj.data.ListK;
import forj.data.OptionalK;
import forj.effect.IO;
import forj.examples.typeclasses.Money;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

@SuppressWarnings("rawtypes")
class GenericTest {

    @Test
    void listMeansEveryCombination() {
        var sums = Generic.addBoth(new ListK<>(List.of(1, 2)), new ListK<>(List.of(10, 20)));
        assertEquals(List.of(11, 21, 12, 22), ListK.narrow(sums));
    }

    @Test
    void optionalMeansBothOrNothing() {
        assertEquals(Optional.of(3), OptionalK.narrow(Generic.addBoth(new OptionalK<>(Optional.of(1)), new OptionalK<>(Optional.of(2)))));
        assertEquals(Optional.empty(), OptionalK.narrow(Generic.addBoth(new OptionalK<>(Optional.of(1)), new OptionalK<>(Optional.empty()))));
    }

    @Test
    void ioMeansOneAfterTheOtherWhenRun() throws Exception {
        var calls = new AtomicInteger();
        var sum = Generic.addBoth(IO.delay(calls::incrementAndGet), IO.delay(calls::incrementAndGet));
        assertEquals(0, calls.get());               // nothing has run
        assertEquals(3, IO.narrow(sum).unsafeRunSync());
        assertEquals(2, calls.get());
    }

    @Test
    void applicativeIsEnoughForIndependentSteps() throws Exception {
        assertEquals(List.of(11, 21, 12, 22),
                ListK.narrow(Generic.addIndependent(new ListK<>(List.of(1, 2)), new ListK<>(List.of(10, 20)))));
        IO<Integer> sum = Generic.addIndependent(IO.pure(1), IO.pure(2));
        assertEquals(3, sum.unsafeRunSync());
    }

    @Test
    void theSemigroupIsPickedByTheValueType() throws Exception {
        // same function, four meanings of "combine": chosen by A at each call site
        Kind<Optional, Integer> ints = Generic.combineBoth(new OptionalK<>(Optional.of(1)), new OptionalK<>(Optional.of(2)));
        Kind<Optional, String> strings = Generic.combineBoth(new OptionalK<>(Optional.of("a")), new OptionalK<>(Optional.of("b")));
        Kind<Optional, List<Integer>> lists = Generic.combineBoth(new OptionalK<>(Optional.of(List.of(1))), new OptionalK<>(Optional.of(List.of(2, 3))));
        IO<Money> money = Generic.combineBoth(IO.pure(new Money(150)), IO.pure(new Money(275)));
        assertEquals(Optional.of(3), OptionalK.narrow(ints));
        assertEquals(Optional.of("ab"), OptionalK.narrow(strings));
        assertEquals(Optional.of(List.of(1, 2, 3)), OptionalK.narrow(lists));
        assertEquals(new Money(425), money.unsafeRunSync());
    }

    @Test
    void combineAllRunsEveryEffectAndCombinesTheResults() throws Exception {
        var calls = new AtomicInteger();
        List<IO<Integer>> steps = List.of(IO.delay(() -> calls.incrementAndGet() * 10), IO.pure(5), IO.pure(7));
        IO<Integer> total = Generic.combineAll(new ListK<>(steps));
        assertEquals(0, calls.get());
        assertEquals(22, total.unsafeRunSync());

        IO<Money> none = Generic.combineAll(new ListK<IO<Money>>(List.of()));
        assertEquals(new Money(0), none.unsafeRunSync());

        Kind<Optional, String> greeting = Generic.combineAll(new ListK<>(List.of(
                new OptionalK<>(Optional.of("hello, ")), new OptionalK<>(Optional.of("world")))));
        assertEquals(Optional.of("hello, world"), OptionalK.narrow(greeting));
    }
}
