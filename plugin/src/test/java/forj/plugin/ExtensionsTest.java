package forj.plugin;

import static forj.plugin.ImplicitsTest.compile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Extension methods: forj.Syntax on type classes, and user `extension` methods. */
class ExtensionsTest {

    @Test
    void typeClassSyntaxOnAGenericF() throws Exception {
        var c = compile(Map.of("app/App.java", """
                package app;
                import forj.data.ListK;
                import forj.effect.IO;
                import forj.typeclass.Monad;
                import java.util.List;
                public class App {
                    static <F<_>> F<Integer> step(F<Integer> fa)(using Monad<F> m) {
                        return fa.map(x -> x * 2).flatMap(x -> m.pure(x + 1));
                    }
                    public static Object run() throws Exception {
                        IO<Integer> io = step(IO.pure(20));
                        return List.of(ListK.narrow(step(new ListK<>(List.of(1, 2)))), io.unsafeRunSync());
                    }
                }
                """));
        assertEquals(List.of(List.of(3, 5), 41), c.call("app.App", "run"));
    }

    @Test
    void combineOnPlainValues() throws Exception {
        var c = compile(Map.of("app/App.java", """
                package app;
                import java.util.List;
                public class App {
                    public static Object run() {
                        Integer one = 1;
                        return List.of(one.combine(2), "a".combine("b"), List.of(1).combine(List.of(2, 3)));
                    }
                }
                """));
        assertEquals(List.of(3, "ab", List.of(1, 2, 3)), c.call("app.App", "run"));
    }

    @Test
    void nestedInsideLambdas() throws Exception {
        var c = compile(Map.of("app/App.java", """
                package app;
                import forj.data.OptionalK;
                import forj.typeclass.Monad;
                import forj.typeclass.Semigroup;
                import java.util.Optional;
                public class App {
                    static <F<_>, A> F<A> both(F<A> fa, F<A> fb)(using Monad<F> m, Semigroup<A> s) {
                        return fa.flatMap(a -> fb.map(b -> a.combine(b)));
                    }
                    public static Object run() {
                        return OptionalK.narrow(both(new OptionalK<>(Optional.of("x")), new OptionalK<>(Optional.of("y"))));
                    }
                }
                """));
        assertEquals(java.util.Optional.of("xy"), c.call("app.App", "run"));
    }

    @Test
    void userExtensionsFromAnImportAndFromTheReceiversClass() throws Exception {
        var c = compile(Map.of(
                "lib/Show.java", """
                        package lib;
                        public interface Show<A> {
                            String show(A a);
                            given Show<Integer> integer = i -> "#" + i;
                            extension <A> String show(A a)(using Show<A> s) { return s.show(a); }
                        }
                        """,
                "lib/Money.java", """
                        package lib;
                        public record Money(long cents) {
                            // found through the receiver's type: no import needed
                            extension String pretty(Money m) { return "$" + m.cents() / 100; }
                        }
                        """,
                "app/App.java", """
                        package app;
                        import static lib.Show.*;
                        import java.util.List;
                        import lib.Money;
                        public class App {
                            public static Object run() {
                                Integer n = 7;
                                return List.of(n.show(), new Money(1234).pretty());
                            }
                        }
                        """));
        assertEquals(List.of("#7", "$12"), c.call("app.App", "run"));
    }

    @Test
    void theTypesOwnMethodWins() throws Exception {
        var c = compile(Map.of("app/App.java", """
                package app;
                import java.util.Optional;
                public class App {
                    public static Object run() { return Optional.of(1).map(x -> x + 1); }  // Optional.map, not Syntax.map
                }
                """));
        assertEquals(java.util.Optional.of(2), c.call("app.App", "run"));
    }

    @Test
    void aMissingCapabilityIsACompileError() throws Exception {
        var c = compile(Map.of("app/App.java", """
                package app;
                import forj.data.CallableK;
                public class App {
                    static Object run() { return new CallableK<>(() -> 1).filter(x -> x > 0); }
                }
                """));
        assertTrue(c.errors().stream().anyMatch(e -> e.contains("forj: no given forj.typeclass.FunctorFilter<java.util.concurrent.Callable>")),
                c.errors()::toString);
    }
}
