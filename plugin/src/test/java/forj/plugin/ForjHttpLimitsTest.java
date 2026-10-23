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

    /** forj-http's middleware shape: an F-generic app, and middleware that only needs F's instances. */
    static final String APP = """
            package lib;
            import forj.typeclass.Applicative;
            import forj.typeclass.MonadError;
            import java.util.function.Function;
            public final class HttpApp<F<_>> {
                public interface Middleware<G<_>> { Function<String, G<String>> wrap(Function<String, G<String>> next); }
                private final Function<String, F<String>> run;
                public HttpApp(Function<String, F<String>> run) { this.run = run; }

                public HttpApp<F> through(Middleware<F> m) { return new HttpApp<>(m.wrap(run)); }

                public F<String> apply(String request) { return run.apply(request); }

                public static <F<_>> Middleware<F> errors()(using MonadError<F, Throwable> me) {
                    return next -> req -> me.handleErrorWith(next.apply(req), e -> me.pure("500 " + e.getMessage()));
                }

                public static <F<_>> Middleware<F> tagged(String tag)(using Applicative<F> ap) {
                    return next -> req -> ap.map(next.apply(req), body -> tag + body);
                }

                /** Leaves its F unconstrained: speculated alone, it is a Route<Object>. */
                public static <F<_>> Routes.Route<F> constant(String body) {
                    return () -> { throw new UnsupportedOperationException(body); };
                }
            }
            """;

    @Test
    void aUsingCallAsAnArgumentTakesFFromTheParameter() throws Exception {
        var c = compile(Map.of("lib/Routes.java", ROUTES, "lib/HttpApp.java", APP, "app/App.java", """
                package app;
                import static lib.HttpApp.errors;
                import static lib.HttpApp.tagged;
                import forj.effect.IO;
                import java.util.List;
                import lib.HttpApp;
                public class App {
                    public static Object run() throws Exception {
                        HttpApp<IO> app = new HttpApp<IO>(req -> req.isEmpty() ? IO.raiseError(new IllegalStateException("empty")) : IO.pure("200 " + req));
                        HttpApp<IO> served = app.through(errors()).through(tagged("> "));
                        return List.of(((IO<String>) served.apply("hi")).runSync(), ((IO<String>) served.apply("")).runSync());
                    }
                }
                """));
        assertEquals(List.of("> 200 hi", "> 500 empty"), c.call("app.App", "run"));
    }

    @Test
    void anArgumentLeavingFUnconstrainedYieldsToTheTarget() throws Exception {
        var c = compile(Map.of("lib/Routes.java", ROUTES, "lib/HttpApp.java", APP, "app/App.java", """
                package app;
                import static lib.HttpApp.constant;
                import forj.effect.IO;
                import lib.Routes;
                public class App {
                    public static Object run() {
                        Routes<IO> routes = Routes.of(constant("a"), constant("b"));   // F is IO, not Object
                        return routes.size();
                    }
                }
                """));
        assertEquals(2, c.call("app.App", "run"));
    }

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
