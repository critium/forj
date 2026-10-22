package forj.plugin;

import static forj.plugin.ImplicitsTest.compile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Opt-in lifting of JDK types (forj.Language): results, receivers and arguments. */
class LiftingTest {

    private static final String GENERIC = """
            static <F, A> Kind<F, A> pureOf(A a)(using Applicative<F> ap) { return ap.pure(a); }
            static <F> Kind<F, Integer> addBoth(Kind<F, Integer> a, Kind<F, Integer> b)(using Monad<F> m) {
                return m.flatMap(a, x -> m.map(b, y -> x + y));
            }
            """;

    private static final String IMPORTS = """
            import forj.Kind;
            import forj.typeclass.Applicative;
            import forj.typeclass.Monad;
            import java.util.List;
            import java.util.Optional;
            import java.util.concurrent.Callable;
            """;

    @Test
    void liftResultsInfersFFromAJdkTargetAndUnwraps() throws Exception {
        var c = compile(Map.of("app/App.java", """
                package app;
                import static forj.Language.liftResults;
                %s
                public class App {
                    %s
                    static Callable<String> returned() { return pureOf("r"); }
                    public static Object run() throws Exception {
                        Callable<String> local = pureOf("x");
                        Optional<Integer> optional = pureOf(3);
                        List<Integer> list;
                        list = pureOf(4);
                        return List.of(local.call(), returned().call(), optional, list);
                    }
                }
                """.formatted(IMPORTS, GENERIC)));
        assertEquals(List.of("x", "r", Optional.of(3), List.of(4)), c.call("app.App", "run"));
    }

    @Test
    void withoutTheImportNothingIsLifted() throws Exception {
        var c = compile(Map.of("app/App.java", """
                package app;
                %s
                public class App {
                    %s
                    static Object run() { Callable<String> local = pureOf("x"); return local; }
                }
                """.formatted(IMPORTS, GENERIC)));
        assertTrue(!c.errors().isEmpty(), "expected a compile error without import static forj.Language.liftResults");
    }

    @Test
    void eachSwitchOnlyTurnsOnItsOwnFeature() throws Exception {
        var c = compile(Map.of("app/App.java", """
                package app;
                import static forj.Language.liftResults;
                %s
                public class App {
                    static Object run() { List<Integer> tens = List.of(1, 2).map(x -> x * 10); return tens; }
                }
                """.formatted(IMPORTS)));
        assertTrue(c.errors().stream().anyMatch(e -> e.contains("map")), c.errors()::toString);
    }

    @Test
    void liftArgumentsWrapsJdkValuesPassedAsKinds() throws Exception {
        var c = compile(Map.of("app/App.java", """
                package app;
                import static forj.Language.liftArguments;
                import static forj.Language.liftResults;
                %s
                public class App {
                    %s
                    public static Object run() throws Exception {
                        List<Integer> sums = addBoth(List.of(1, 2), List.of(10));
                        Callable<Integer> one = () -> 1;
                        Callable<Integer> two = () -> 2;
                        Callable<Integer> three = addBoth(one, two);
                        Optional<Integer> none = addBoth(Optional.of(1), Optional.<Integer>empty());
                        return List.of(sums, three.call(), none);
                    }
                }
                """.formatted(IMPORTS, GENERIC)));
        assertEquals(List.of(List.of(11, 12), 3, Optional.empty()), c.call("app.App", "run"));
    }

    @Test
    void liftReceiversGivesJdkValuesTheTypeClassSyntax() throws Exception {
        var c = compile(Map.of("app/App.java", """
                package app;
                import static forj.Language.lifting;
                %s
                public class App {
                    public static Object run() throws Exception {
                        List<Integer> tens = List.of(1, 2).map(x -> x * 10);
                        List<Integer> chained = List.of(1, 2, 3).map(x -> x * 2).filter(x -> x > 2);
                        Callable<Integer> c = () -> 20;
                        Callable<Integer> next = c.map(x -> x + 1);
                        Optional<Integer> own = Optional.of(1).map(x -> x + 1);   // Optional.map itself
                        return List.of(tens, chained, next.call(), own);
                    }
                }
                """.formatted(IMPORTS)));
        assertEquals(List.of(List.of(10, 20), List.of(4, 6), 21, Optional.of(2)), c.call("app.App", "run"));
    }

    @Test
    void aLibraryTypeLiftsThroughItsOwnForjLiftAndLower() throws Exception {
        var c = compile(Map.of(
                "lib/Boxes.java", """
                        package lib;
                        import forj.Kind;
                        import forj.Lower;
                        import forj.typeclass.Functor;
                        import java.util.function.Function;
                        @SuppressWarnings("rawtypes")
                        public final class Boxes {
                            public record Box<A>(A value) {}
                            record BoxK<A>(Box<A> box) implements Kind<Box, A> {}
                            public static <A> Kind<Box, A> forjLift(Box<A> b) { return new BoxK<>(b); }
                            @Lower public static <A> Box<A> lowerBox(Kind<Box, A> k) { return ((BoxK<A>) k).box(); }
                            given Functor<Box> functor = new Functor<>() {
                                @Override
                                public <A, B> Kind<Box, B> map(Kind<Box, A> fa, Function<? super A, ? extends B> f) {
                                    return new BoxK<>(new Box<>(f.apply(lowerBox(fa).value())));
                                }
                            };
                        }
                        """,
                "app/App.java", """
                        package app;
                        import static forj.Language.lifting;
                        import static lib.Boxes.*;
                        public class App {
                            public static Object run() {
                                Box<Integer> b = new Box<>(1).map(x -> x + 41);
                                return b.value();
                            }
                        }
                        """));
        assertEquals(42, c.call("app.App", "run"));
    }

    @Test
    void aMissingCapabilityIsStillACompileError() throws Exception {
        var c = compile(Map.of("app/App.java", """
                package app;
                import static forj.Language.lifting;
                %s
                public class App {
                    static Object run() {
                        Callable<Integer> c = () -> 1;
                        Callable<Integer> kept = c.filter(x -> x > 0);
                        return kept;
                    }
                }
                """.formatted(IMPORTS)));
        assertTrue(c.errors().stream().anyMatch(e -> e.contains("forj: no given forj.typeclass.FunctorFilter<java.util.concurrent.Callable>")),
                c.errors()::toString);
    }
}
