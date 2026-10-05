package forj.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringWriter;
import java.net.URI;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

/** s"...", f"..." and raw"...": compiles a method body with the plugin and runs it. */
class InterpolationTest {

    private static final String PREFIX = """
            import java.util.*;
            public class Snippet {
                public static Object run() {
            """;

    private static List<Diagnostic<? extends JavaFileObject>> errors;

    private static Object run(String body) throws Exception {
        String source = PREFIX + body + "\n    }\n}\n";
        JavaFileObject file = new SimpleJavaFileObject(URI.create("string:///Snippet.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        String cp = System.getProperty("java.class.path");
        Path out = Files.createTempDirectory("forj-interpolation");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        boolean ok = ToolProvider.getSystemJavaCompiler().getTask(new StringWriter(), null, diagnostics,
                List.of("--enable-preview", "--source", "28", "-classpath", cp, "-processorpath", cp,
                        "-Xplugin:Forj", "-d", out.toString()),
                null, List.of(file)).call();
        errors = diagnostics.getDiagnostics().stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR).toList();
        if (!ok) {
            return null;
        }
        try (var loader = new URLClassLoader(new java.net.URL[] {out.toUri().toURL()}, InterpolationTest.class.getClassLoader())) {
            return loader.loadClass("Snippet").getMethod("run").invoke(null);
        }
    }

    private static List<String> messages() {
        return errors.stream().map(d -> d.getMessage(null)).toList();
    }

    @Test
    void interpolatesNames() throws Exception {
        assertEquals("Hi ana, you are 7!", run("""
                String name = "ana";
                int age = 7;
                return s"Hi $name, you are $age!";
                """));
    }

    @Test
    void interpolatesExpressionsIncludingQuotesAndBraces() throws Exception {
        assertEquals("v=1 sum=5 set=[x]", run("""
                Map<String, Integer> m = Map.of("k", 1);
                int a = 2, b = 3;
                return s"v=${m.get("k")} sum=${a + b} set=${new TreeSet<>(List.of("x"))}";
                """));
    }

    @Test
    void numbersStillConcatenateAsText() throws Exception {
        assertEquals("12", run("""
                return s"${1}${2}";
                """));
    }

    @Test
    void handlesDollarsEscapesAndCallsOnTheResult() throws Exception {
        assertEquals(List.of("$5", "a\tb", 5), run("""
                int x = 5;
                String tab = "b";
                return List.of(s"$$$x", s"a\\t$tab", s"ab$tab$x".length() + 1);
                """));
    }

    @Test
    void formatsWithF() throws Exception {
        assertEquals("pen   |    2.50|3|100%", run("""
                String item = "pen";
                double price = 2.5;
                int n = 3;
                return f"$item%-6s|$price%8.2f|$n|100%%";
                """));
    }

    @Test
    void keepsBackslashesWithRaw() throws Exception {
        assertEquals("C:\\temp\\x.txt\\n", run("""
                String file = "x.txt";
                return raw"C:\\temp\\$file\\n";
                """));
    }

    @Test
    void worksInsideForj() throws Exception {
        assertEquals(List.of("#1", "#2"), run("""
                return forj {
                    x <- List.of(1, 2);
                } yield s"#$x";
                """));
    }

    @Test
    void leavesCommentsStringsAndVariablesNamedSAlone() throws Exception {
        assertEquals("s\"$x\" 2", run("""
                String s = "ab";
                // s"$ignored" in a comment
                return "s\\"$x\\"" + " " + s.length();
                """));
    }

    @Test
    void reportsABadDollar() throws Exception {
        run("""
                return s"cost: $ 5";
                """);
        assertTrue(messages().stream().anyMatch(m -> m.contains("forj: $ must be followed by a name")), messages()::toString);
    }

    @Test
    void rejectsBackslashDollarOutsideRaw() throws Exception {
        run("""
                return s"cost: \\$5";
                """);
        assertTrue(messages().stream().anyMatch(m -> m.contains("forj: write $$ for a literal $")), messages()::toString);
    }

    @Test
    void errorsInsideAnExpressionPointAtIt() throws Exception {
        String line = "        return s\"total ${missing + 1}\";";
        run(line);
        var d = errors.getFirst();
        assertTrue(d.getMessage(null).contains("missing"), messages()::toString);
        assertEquals(PREFIX.lines().count() + 1, d.getLineNumber());
        assertEquals(line.indexOf("missing") + 1, d.getColumnNumber());
    }
}
