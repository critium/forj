package forj.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringWriter;
import java.net.URI;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

/** given / using: compiles small programs with the plugin, then runs them. */
class ImplicitsTest {

    private record Compiled(Path classes, List<String> errors, Path... libraries) {
        Object call(String className, String method) throws Exception {
            assertEquals(List.of(), errors);
            var cp = new ArrayList<java.net.URL>();
            cp.add(classes.toUri().toURL());
            for (Path library : libraries) {
                cp.add(library.toUri().toURL());
            }
            for (String entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
                cp.add(Path.of(entry).toUri().toURL());
            }
            try (var loader = new URLClassLoader(cp.toArray(java.net.URL[]::new), null)) {
                return loader.loadClass(className).getMethod(method).invoke(null);
            }
        }
    }

    private static Compiled compile(Map<String, String> sources, Path... classpath) throws Exception {
        List<JavaFileObject> files = new ArrayList<>();
        sources.forEach((name, code) -> files.add(new SimpleJavaFileObject(
                URI.create("string:///" + name), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return code;
            }
        }));
        StringBuilder cp = new StringBuilder(System.getProperty("java.class.path"));
        for (Path p : classpath) {
            cp.append(java.io.File.pathSeparator).append(p);
        }
        Path out = Files.createTempDirectory("forj-implicits");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        ToolProvider.getSystemJavaCompiler().getTask(new StringWriter(), null, diagnostics,
                List.of("--enable-preview", "--source", "28", "-classpath", cp.toString(),
                        "-processorpath", System.getProperty("java.class.path"), "-Xplugin:Forj",
                        "-d", out.toString()),
                null, files).call();
        List<String> errors = diagnostics.getDiagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .map(d -> d.getMessage(null))
                .toList();
        return new Compiled(out, errors, classpath);
    }

    private static final String ORD = """
            package lib;
            public interface Ord<A> {
                int compare(A a, A b);
            }
            """;

    private static final String ORDS = """
            package lib;
            import java.util.List;
            public class Ords {
                given Ord<Integer> integer = Integer::compare;

                given <A> Ord<List<A>> list(using Ord<A> elem) {
                    return (x, y) -> {
                        for (int i = 0; i < Math.min(x.size(), y.size()); i++) {
                            int c = elem.compare(x.get(i), y.get(i));
                            if (c != 0) return c;
                        }
                        return Integer.compare(x.size(), y.size());
                    };
                }

                public static <A> A max(List<A> xs) using Ord<A> ord {
                    A best = xs.getFirst();
                    for (A x : xs) if (ord.compare(x, best) > 0) best = x;
                    return best;
                }
            }
            """;

    @Test
    void passesAGivenToAUsingParameter() throws Exception {
        var c = compile(Map.of("lib/Ord.java", ORD, "lib/Ords.java", ORDS, "app/App.java", """
                package app;
                import static lib.Ords.*;
                import java.util.List;
                public class App {
                    public static Object run() { return max(List.of(3, 7, 2)); }
                }
                """));
        assertEquals(7, c.call("app.App", "run"));
    }

    @Test
    void derivesInstancesFromOtherInstances() throws Exception {
        var c = compile(Map.of("lib/Ord.java", ORD, "lib/Ords.java", ORDS, "app/App.java", """
                package app;
                import static lib.Ords.*;
                import java.util.List;
                public class App {
                    public static Object run() {
                        return max(List.of(List.of(List.of(1, 2)), List.of(List.of(1, 3)), List.of(List.of(1))));
                    }
                }
                """));
        assertEquals(List.of(List.of(1, 3)), c.call("app.App", "run"));
    }

    @Test
    void forwardsTheEnclosingMethodsUsingParameter() throws Exception {
        var c = compile(Map.of("lib/Ord.java", ORD, "lib/Ords.java", ORDS, "app/App.java", """
                package app;
                import static lib.Ords.*;
                import lib.Ord;
                import java.util.List;
                public class App {
                    static <A> A larger(A a, A b) using Ord<A> ord {
                        return max(List.of(a, b));       // gets `ord`, not a global given
                    }
                    public static Object run() {
                        return larger(4, 9);
                    }
                }
                """));
        assertEquals(9, c.call("app.App", "run"));
    }

    @Test
    void findsGivensNextToTheTypeClassAndTheDataType() throws Exception {
        var c = compile(Map.of(
                "lib/Show.java", """
                        package lib;
                        public interface Show<A> {
                            String show(A a);
                            given Show<Integer> integer = i -> "#" + i;
                            static <A> String render(A a) using Show<A> s { return s.show(a); }
                        }
                        """,
                "lib/Money.java", """
                        package lib;
                        public record Money(long cents) {
                            given Show<Money> show = m -> "$" + m.cents() / 100;
                        }
                        """,
                "app/App.java", """
                        package app;
                        import lib.Money;
                        import lib.Show;
                        public class App {
                            public static Object run() {
                                return Show.render(42) + " " + Show.render(new Money(1500));
                            }
                        }
                        """));
        assertEquals("#42 $15", c.call("app.App", "run"));
    }

    @Test
    void summonsAGiven() throws Exception {
        var c = compile(Map.of("lib/Ord.java", ORD, "lib/Ords.java", ORDS, "app/App.java", """
                package app;
                import static lib.Ords.*;
                import forj.Implicits;
                import lib.Ord;
                public class App {
                    public static Object run() {
                        Ord<Integer> ord = Implicits.<Ord<Integer>>summon();
                        return ord.compare(1, 2);
                    }
                }
                """));
        assertEquals(-1, c.call("app.App", "run"));
    }

    @Test
    void findsGivensInCompiledLibraries() throws Exception {
        var library = compile(Map.of("lib/Ord.java", ORD, "lib/Ords.java", ORDS));
        assertEquals(List.of(), library.errors());

        var c = compile(Map.of("app/App.java", """
                package app;
                import static lib.Ords.*;
                import java.util.List;
                public class App {
                    public static Object run() { return max(List.of(List.of(5), List.of(6))); }
                }
                """), library.classes());
        assertEquals(List.of(6), c.call("app.App", "run"));
    }

    @Test
    void usesLocalGivensDeclaredEarlierInTheBlock() throws Exception {
        var c = compile(Map.of("lib/Ord.java", ORD, "lib/Ords.java", ORDS, "app/App.java", """
                package app;
                import lib.Ord;
                import lib.Ords;
                import java.util.List;
                public class App {
                    public static Object run() {
                        given Ord<Integer> reversed = (a, b) -> Integer.compare(b, a);
                        return Ords.max(List.of(3, 7, 2));      // the local given wins
                    }
                }
                """));
        assertEquals(2, c.call("app.App", "run"));
    }

    @Test
    void reportsAMissingGiven() throws Exception {
        var c = compile(Map.of("lib/Ord.java", ORD, "lib/Ords.java", ORDS, "app/App.java", """
                package app;
                import static lib.Ords.*;
                import java.util.List;
                public class App {
                    public static Object run() { return max(List.of("a", "b")); }
                }
                """));
        assertTrue(c.errors().stream().anyMatch(e -> e.contains("forj: no given lib.Ord<java.lang.String> for max")),
                c.errors()::toString);
    }

    @Test
    void reportsAmbiguousGivens() throws Exception {
        var c = compile(Map.of("lib/Ord.java", ORD, "lib/Ords.java", ORDS, "app/App.java", """
                package app;
                import static lib.Ords.*;
                import lib.Ord;
                import java.util.List;
                public class App {
                    given Ord<Integer> natural = Integer::compare;
                    given Ord<Integer> reversed = (a, b) -> Integer.compare(b, a);
                    public static Object run() { return max(List.of(1, 2)); }
                }
                """));
        assertTrue(c.errors().stream().anyMatch(e -> e.contains("forj: ambiguous givens for lib.Ord<java.lang.Integer>")
                && e.contains("natural") && e.contains("reversed")), c.errors()::toString);
    }

    @Test
    void reportsATypeItCannotInfer() throws Exception {
        var c = compile(Map.of("lib/Ord.java", ORD, "app/App.java", """
                package app;
                import lib.Ord;
                public class App {
                    static <A> int same() using Ord<A> ord { return 0; }
                    public static Object run() { return same(); }
                }
                """));
        assertTrue(c.errors().stream().anyMatch(e -> e.contains("forj: cannot infer the type of using parameter")),
                c.errors()::toString);
    }
}
