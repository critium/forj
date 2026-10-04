package forj.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ExamplesTest {

    @Test
    void optionalChainsAndShortCircuits() {
        assertEquals(Optional.of("Lisbon"), Examples.managersCity("ana"));
        assertEquals(Optional.empty(), Examples.managersCity("bo"));     // no manager
        assertEquals(Optional.empty(), Examples.managersCity("nobody")); // no user
    }

    @Test
    void listGeneratorsWithGuard() {
        assertEquals(
                List.of(List.of(3, 4, 5), List.of(5, 12, 13), List.of(6, 8, 10)),
                Examples.pythagoreanTriples(13));
    }

    @Test
    void valueDefinitionsBetweenGenerators() {
        assertEquals(List.of("2♠", "2♥", "K♠", "K♥"), Examples.labels());
    }

    @Test
    void anonymousBind() {
        assertEquals(Optional.of(5), Examples.divide(10, 2));
        assertEquals(Optional.empty(), Examples.divide(10, 0));
    }

    @Test
    void nestedComprehensionsAndThirdPartyInstance() {
        assertEquals(List.of("A1", "A2", "B1", "B2"), Examples.nestedWithStreams());
    }

    @Test
    void noImportNeededForJdkTypes() {
        // this file never imports forj.For; the plugin adds it
        List<Integer> r = forj {
            x <- List.of(1, 2);
            y <- List.of(10, 20);
        } yield x * y;
        assertEquals(List.of(10, 20, 20, 40), r);
    }

    @Test
    void resultIsImmutableLikeScalaList() {
        List<Integer> r = forj {
            x <- List.of(1);
        } yield x;
        assertThrows(UnsupportedOperationException.class, () -> r.add(2));
    }

    @Test
    void blockCanHoldValueDefinitionsAndSwitchExpressions() {
        List<String> r = forj {
            n <- List.of(1, 2, 3);
            var size = switch (n) {
                case 1 -> "one";
                default -> {
                    yield "many"; // belongs to the switch, not the comprehension
                }
            };
        } yield n == 3 ? size + "!" : size;
        assertEquals(List.of("one", "many", "many!"), r);
    }

    @Test
    void forjIsAnExpression() {
        assertEquals(List.of(2, 4), forj { x <- List.of(1, 2); } yield x * 2);
        assertEquals(Optional.of(3), forj { a <- Optional.of(1); b <- Optional.of(2); } yield a + b);
    }

    @Test
    void forjInsideAYield() {
        List<List<Integer>> r = forj {
            x <- List.of(1, 2);
        } yield forj {
            y <- List.of(10, 20);
        } yield x + y;
        assertEquals(List.of(List.of(11, 21), List.of(12, 22)), r);
    }

    @Test
    void plainLessThanMinusIsStillAComparison() {
        int a = 1, b = 0;
        boolean[] flags = {a<-b, b<-a};
        int count = 0;
        for (int i = 3; i<-(-5); i++) {
            count++;
        }
        String s = "x <- y"; // x <- y in a comment
        assertEquals(false, flags[0]);
        assertEquals(false, flags[1]);
        assertEquals(2, count);
        assertEquals("x <- y", s);
    }
}
