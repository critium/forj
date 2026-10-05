package forj.examples.typeclasses;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class ReportTest {

    @Test
    void rendersWithGivenInstances() {
        assertEquals(List.of(
                "count: 3",
                "names: [\"ana\", \"bo\"]",
                "balance: $123.45",
                "history: [Some($5.00), None]"), Report.lines());
    }
}
