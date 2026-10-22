package forj.plugin;

import static forj.plugin.ImplicitsTest.compile;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Code the plugin generates refers to classes by their full names (forj.For, forj.Syntax,
 * lib.Ords); a local variable named like the first package must not capture them.
 */
class ShadowingTest {

    @Test
    void anInsertedGivenIsNotCapturedByALocalNamedLikeItsPackage() throws Exception {
        var c = compile(Map.of(
                "lib/Ords.java", """
                        package lib;
                        import java.util.List;
                        public class Ords {
                            public interface Ord<A> { int compare(A a, A b); }
                            given Ord<Integer> integer = Integer::compare;
                            public static <A> A max(List<A> xs)(using Ord<A> ord) {
                                A best = xs.getFirst();
                                for (A x : xs) if (ord.compare(x, best) > 0) best = x;
                                return best;
                            }
                        }
                        """,
                "app/App.java", """
                        package app;
                        import static lib.Ords.*;
                        import java.util.List;
                        public class App {
                            public static Object run() {
                                int lib = 1;                                   // shadows the lib package
                                return max(List.of(3, 7, 2)) + lib;            // inserts lib.Ords.integer
                            }
                        }
                        """));
        assertEquals(8, c.call("app.App", "run"));
    }

    @Test
    void anExtensionCallIsNotCapturedByALocalNamedForj() throws Exception {
        var c = compile(Map.of("app/App.java", """
                package app;
                public class App {
                    public static Object run() {
                        Integer forj = 1;                  // becomes forj.Syntax.combine(forj, 2, forj.Instances.intSum)
                        return forj.combine(2);
                    }
                }
                """));
        assertEquals(3, c.call("app.App", "run"));
    }

    @Test
    void aComprehensionIsNotCapturedByALocalNamedForj() throws Exception {
        var c = compile(Map.of("app/App.java", """
                package app;
                import java.util.Optional;
                public class App {
                    public static Object run() {
                        String forj = "abc";
                        Optional<Integer> r = forj {
                            a <- Optional.of(1);
                            b <- Optional.of(2);
                        } yield a + b + forj.length();
                        return r;
                    }
                }
                """));
        assertEquals(Optional.of(6), c.call("app.App", "run"));
    }

    @Test
    void aLiftedReceiverIsNotCapturedByALocalNamedForj() throws Exception {
        var c = compile(Map.of("app/App.java", """
                package app;
                import static forj.Language.lifting;
                import java.util.List;
                public class App {
                    public static Object run() {
                        List<Integer> forj = List.of(1, 2);
                        List<Integer> r = forj.map(x -> x * 10);   // forj.For.<Integer>forjLift(forj), lowered back
                        return r;
                    }
                }
                """));
        assertEquals(List.of(10, 20), c.call("app.App", "run"));
    }
}
