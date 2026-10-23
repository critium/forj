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
    void anOverloadIsPickedByItsOrdinaryParametersNotItsUsingCount() throws Exception {
        var c = compile(Map.of("lib/Over.java", """
                package lib;
                import forj.typeclass.Applicative;
                import java.util.function.Consumer;
                public final class Over {
                    public static <F<_>> F<String> log()(using Applicative<F> ap) { return ap.pure("plain"); }
                    public static <F<_>> F<String> log(Consumer<String> sink)(using Applicative<F> ap) {
                        sink.accept("x");
                        return ap.pure("sink");
                    }
                }
                """, "app/App.java", """
                package app;
                import static lib.Over.log;
                import forj.effect.IO;
                import java.util.List;
                public class App {
                    public static Object run() throws Exception {
                        IO<String> a = log();
                        IO<String> b = log(s -> {});
                        IO<String> c = log((String s) -> {});
                        IO<String> d = log(IO.concurrent);                // the instance, passed explicitly
                        return List.of(a.runSync(), b.runSync(), c.runSync(), d.runSync());
                    }
                }
                """));
        assertEquals(List.of("plain", "sink", "sink", "plain"), c.call("app.App", "run"));
    }

    @Test
    void anOverridingUsingMethodIsNotAmbiguousWithTheOneItOverrides() throws Exception {
        var c = compile(Map.of("lib/Starter.java", """
                package lib;
                import forj.typeclass.Applicative;
                public interface Starter {
                    <F<_>> F<String> start(String name)(using Applicative<F> ap);
                }
                """, "lib/Impl.java", """
                package lib;
                import forj.typeclass.Applicative;
                public final class Impl implements Starter {
                    public <F<_>> F<String> start(String name)(using Applicative<F> ap) { return ap.pure("started " + name); }
                }
                """, "app/App.java", """
                package app;
                import forj.effect.IO;
                import java.util.List;
                import lib.Impl;
                import lib.Starter;
                public class App {
                    public static Object run() throws Exception {
                        Starter s = new Impl();
                        IO<String> a = s.start("a");
                        IO<String> b = new Impl().start("b");
                        return List.of(a.runSync(), b.runSync());
                    }
                }
                """));
        assertEquals(List.of("started a", "started b"), c.call("app.App", "run"));
    }

    /** forj-http's JSON bridge: a codec type class, and a request to read bodies from. */
    static final String JSON = """
            package lib;
            import java.util.function.Function;
            public final class Json {
                public interface Codec<A> { String write(A a); A read(String s); }
                public record Request(String body) {}
                public record Entity(String text) {}
                public interface Decoder<A> { A decode(String body, boolean strict); }
                public static <A> A decode(String s)(using Codec<A> c) { return c.read(s); }

                public static <A> Entity json(A value)(using Codec<A> c) { return new Entity(c.write(value)); }
                extension <A> A json(Request request)(using Codec<A> c) { return c.read(request.body()); }

                public static <A> Decoder<A> decoder()(using Codec<A> c) {
                    return (body, strict) -> {
                        if (strict) return decode(body.strip());
                        return decode(body);
                    };
                }

                public static <A> A either(String s, boolean trim)(using Codec<A> c) {
                    A v = trim ? decode(s.strip()) : decode(s);
                    return v;
                }
            }
            """;

    static final String USERS = """
            package app;
            import lib.Json.Codec;
            public record User(String name) {
                given Codec<User> codec = new Codec<>() {
                    public String write(User u) { return "{" + u.name() + "}"; }
                    public User read(String s) { return new User(s.replace("{", "").replace("}", "").strip()); }
                };
            }
            """;

    @Test
    void aGenericOverloadAndAConcreteOneArePickedByTheirOrdinaryParameters() throws Exception {
        var c = compile(Map.of("lib/Json.java", JSON, "app/User.java", USERS, "app/App.java", """
                package app;
                import static lib.Json.*;
                import java.util.List;
                import lib.Json.Request;
                public class App {
                    public static Object run() {
                        Entity e = json(new User("ana"));            // only json(A) takes a User
                        User u = new Request("{bo}").json();          // the extension on Request
                        User v = json(new Request("{cy}"));           // both fit: the more specific json(Request)
                        return List.of(e.text(), u.name(), v.name());
                    }
                }
                """));
        assertEquals(List.of("{ana}", "bo", "cy"), c.call("app.App", "run"));
    }

    @Test
    void aReturnInALambdaAndBothBranchesOfAConditionalGetTheirTarget() throws Exception {
        var c = compile(Map.of("lib/Json.java", JSON, "app/User.java", USERS, "app/App.java", """
                package app;
                import java.util.List;
                import lib.Json;
                public class App {
                    public static Object run() {
                        Json.Decoder<User> d = Json.decoder();
                        User a = Json.either(" {ana} ", true);
                        return List.of(d.decode(" {bo} ", true).name(), d.decode("{cy}", false).name(), a.name());
                    }
                }
                """));
        assertEquals(List.of("bo", "cy", "ana"), c.call("app.App", "run"));
    }

    @Test
    void extensionsAndGivensCanBeAnnotated() throws Exception {
        var c = compile(Map.of("lib/Shout.java", """
                package lib;
                public final class Shout {
                    public interface Show<A> { String show(A a); }
                    @Deprecated(forRemoval = true)
                    extension <A> String shout(A a)(using Show<A> s) { return s.show(a).toUpperCase(); }
                    @SuppressWarnings("unused") @java.lang.Deprecated
                    given Show<String> string = x -> x + "!";
                    public @Deprecated given <A> Show<java.util.List<A>> list(using Show<A> e) {
                        return xs -> xs.stream().map(e::show).toList().toString();
                    }
                }
                """, "app/App.java", """
                package app;
                import static lib.Shout.*;
                import java.util.List;
                public class App {
                    @SuppressWarnings("removal")
                    public static Object run() {
                        @SuppressWarnings("unused") given lib.Shout.Show<Integer> ints = i -> "#" + i;
                        return List.of("hi".shout(), List.of("a", "b").shout(), shout(3));
                    }
                }
                """));
        assertEquals(List.of("HI!", "[A!, B!]", "#3"), c.call("app.App", "run"));
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
