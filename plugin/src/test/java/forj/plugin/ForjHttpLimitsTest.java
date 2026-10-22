package forj.plugin;

import static forj.plugin.ImplicitsTest.compile;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Limits forj-http ran into (TODO.md, "Found while building forj-http"). */
class ForjHttpLimitsTest {

    /** A Routes-like library: F-generic, Applicative-constrained. */
    static final String ROUTES = """
            package lib;
            import forj.typeclass.Applicative;
            import java.util.ArrayList;
            import java.util.List;
            public final class Routes<F<_>> {
                public interface Route<G<_>> { G<String> run(); }
                private final List<Route<F>> routes;
                private Routes(List<Route<F>> routes) { this.routes = routes; }

                public static <F<_>> Routes<F> of(Route<F>... routes)(using Applicative<F> ap) {
                    return new Routes<>(List.of(routes));
                }

                public static <F<_>> Routes<F> named(String name, Route<F>... routes)(using Applicative<F> ap) {
                    List<Route<F>> all = new ArrayList<>(List.of(routes));
                    all.add(() -> ap.pure(name));
                    return new Routes<>(all);
                }

                public F<String> first()(using Applicative<F> ap) {
                    return routes.isEmpty() ? ap.pure("none") : routes.getFirst().run();
                }

                public int size() { return routes.size(); }
            }
            """;

    @Test
    void aNestedTypeCanRedeclareAHigherKindedParameter() throws Exception {
        var c = compile(Map.of("app/Outer.java", """
                package app;
                public class Outer<F<_>> {
                    public interface Inner<F<_>> { F<String> run(); }
                    public static Object run() { return "ok"; }
                }
                """));
        assertEquals("ok", c.call("app.Outer", "run"));
    }

    @Test
    void usingAfterVarargs() throws Exception {
        var c = compile(Map.of("lib/Routes.java", ROUTES, "app/App.java", """
                package app;
                import forj.effect.IO;
                import java.util.List;
                import lib.Routes;
                import lib.Routes.Route;
                public class App {
                    public static Object run() throws Exception {
                        Route<IO> a = () -> IO.pure("a");
                        Route<IO> b = () -> IO.pure("b");
                        Routes<IO> two = Routes.of(a, b);
                        Routes<IO> none = Routes.<IO>of();
                        Routes<IO> named = Routes.named("n", a);
                        Routes<IO> explicit = Routes.of(a, b)(using IO.concurrent);
                        return List.of(two.size(), none.size(), named.size(), explicit.size());
                    }
                }
                """));
        assertEquals(List.of(2, 0, 2, 2), c.call("app.App", "run"));
    }

    @Test
    void anInstanceMethodSeesTheReceiversTypeArguments() throws Exception {
        var c = compile(Map.of("lib/Routes.java", ROUTES, "app/App.java", """
                package app;
                import forj.effect.IO;
                import lib.Routes;
                import lib.Routes.Route;
                public class App {
                    public static Object run() throws Exception {
                        Route<IO> a = () -> IO.pure("a");
                        Routes<IO> routes = Routes.of(a);
                        IO<String> first = (IO<String>) routes.first();
                        return first.runSync();
                    }
                }
                """));
        assertEquals("a", c.call("app.App", "run"));
    }
}
