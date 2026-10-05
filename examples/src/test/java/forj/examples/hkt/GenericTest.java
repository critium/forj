package forj.examples.hkt;

import static org.junit.jupiter.api.Assertions.assertEquals;

import forj.data.ListK;
import forj.data.OptionalK;
import forj.effect.IO;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

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
}
