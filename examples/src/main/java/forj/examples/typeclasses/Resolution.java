package forj.examples.typeclasses;

import java.util.List;

/**
 * Which given gets picked. There are several {@code Show<Integer>} and {@code Show<Money>}
 * instances around; each method below shows one rule. Nearest scope wins:
 *
 * <ol>
 *   <li>a local {@code given} declared earlier in the block</li>
 *   <li>the enclosing method's {@code using} parameters</li>
 *   <li>givens in the enclosing classes</li>
 *   <li>givens brought in with {@code import static} (see {@link ImportedGivens})</li>
 *   <li>givens next to the type class or the data type ({@code Show.integer}, {@code Money.show})</li>
 * </ol>
 *
 * Two matches at the same level is a compile error, not a guess.
 */
public final class Resolution {
    private Resolution() {}

    /** The required type alone picks the instance: Show.integer, Show.string, Money.show. */
    public static List<String> byType() {
        return List.of(Show.show(42), Show.show("hi"), Show.show(new Money(150)));
    }

    /** Derived instances pick each element's instance: Show.list(Show.integer) vs Show.list(Money.show). */
    public static List<String> byElementType() {
        return List.of(Show.show(List.of(1, 2)), Show.show(List.of(new Money(100), new Money(250))));
    }

    /** A local given beats the one next to the type class... */
    public static String localGiven() {
        given Show<Integer> hex = i -> "0x" + Integer.toHexString(i);
        return Show.show(255);                                    // hex, not Show.integer
    }

    /** ...but only inside its own block. */
    public static String withoutLocalGiven() {
        return Show.show(255);                                    // Show.integer again
    }

    /** The local given also flows into everything it's derived into. */
    public static String localGivenInsideADerivedInstance() {
        given Show<Integer> hex = i -> "0x" + Integer.toHexString(i);
        return Show.show(List.of(10, 11));                         // Show.list(hex)
    }

    /** Generic code uses whatever its caller had in scope, via its using parameter. */
    static <A> String twice(A a)(using Show<A> show) {
        return Show.show(a) + " " + Show.show(a);                  // `show`, the caller's choice
    }

    public static List<String> forwardedFromTheCaller() {
        String plain = twice(7);                                   // twice(7, Show.integer)
        given Show<Integer> roman = i -> i == 7 ? "VII" : "?";
        String fancy = twice(7);                                   // twice(7, roman)
        return List.of(plain, fancy);
    }

    /** Passing an argument yourself always wins: nothing is looked up. */
    public static String passedExplicitly() {
        Show<Integer> stars = i -> "*".repeat(i);
        return Show.show(3, stars);
    }

    /** A given in an enclosing class beats Money's own. */
    public static final class Accounting {
        private Accounting() {}

        given Show<Money> ledger = m -> (m.cents() < 0 ? "(" : "") + Math.abs(m.cents()) + "c" + (m.cents() < 0 ? ")" : "");

        public static List<String> report() {
            return List.of(Show.show(new Money(-420)), Show.show(42));   // ledger; Show.integer
        }
    }
}
