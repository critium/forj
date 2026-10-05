package forj.examples.typeclasses;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class ResolutionTest {

    @Test
    void picksByType() {
        assertEquals(List.of("42", "\"hi\"", "$1.50"), Resolution.byType());
    }

    @Test
    void picksEachElementsInstance() {
        assertEquals(List.of("[1, 2]", "[$1.00, $2.50]"), Resolution.byElementType());
    }

    @Test
    void localGivenWinsInItsBlockOnly() {
        assertEquals("0xff", Resolution.localGiven());
        assertEquals("255", Resolution.withoutLocalGiven());
        assertEquals("[0xa, 0xb]", Resolution.localGivenInsideADerivedInstance());
    }

    @Test
    void genericCodeUsesTheCallersInstance() {
        assertEquals(List.of("7 7", "VII VII"), Resolution.forwardedFromTheCaller());
    }

    @Test
    void explicitArgumentWins() {
        assertEquals("***", Resolution.passedExplicitly());
    }

    @Test
    void enclosingClassBeatsTheDataTypesOwn() {
        assertEquals(List.of("(420c)", "42"), Resolution.Accounting.report());
    }

    @Test
    void importBeatsTheDataTypesOwn() {
        assertEquals(List.of("€19,99", "[€0,05]"), ImportedGivens.report());
    }
}
