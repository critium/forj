package forj.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Files;
import java.util.List;
import java.util.stream.Collectors;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

/** Compiles snippets in-process with the plugin enabled and checks what javac reports. */
class DiagnosticsTest {

    private static final String PREFIX = """
            import static forj.For.*;
            import java.util.*;
            class Snippet {
                Object f() {
            """;

    private static List<Diagnostic<? extends JavaFileObject>> compile(String body) throws Exception {
        String source = PREFIX + body + "\n    }\n}\n";
        JavaFileObject file = new SimpleJavaFileObject(URI.create("string:///Snippet.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        String cp = System.getProperty("java.class.path");
        var out = Files.createTempDirectory("forj-test");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var task = ToolProvider.getSystemJavaCompiler().getTask(new StringWriter(), null, diagnostics,
                List.of("--enable-preview", "--source", "28", "-classpath", cp, "-processorpath", cp,
                        "-Xplugin:Forj", "-d", out.toString()),
                null, List.of(file));
        task.call();
        return diagnostics.getDiagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .collect(Collectors.toList());
    }

    private static List<String> errors(String body) throws Exception {
        return compile(body).stream().map(d -> d.getMessage(null)).collect(Collectors.toList());
    }

    private static void assertError(String body, String fragment) throws Exception {
        var errors = errors(body);
        assertTrue(errors.stream().anyMatch(e -> e.contains(fragment)), errors::toString);
    }

    @Test
    void wellFormedComprehensionCompiles() throws Exception {
        assertEquals(List.of(), errors("""
                List<Integer> r = forj {
                    x <- List.of(1, 2);
                    guard(x > 1);
                    y <- List.of(3);
                } yield x + y;
                return r;
                """));
    }

    @Test
    void errorsPointAtTheOriginalColumns() throws Exception {
        // `<-` is rewritten in place, so javac's line/column still match the user's file.
        String body = """
                List<Integer> r = forj {
                    x <- List.of(1, 2);
                } yield x + missing;
                return r;
                """;
        var d = compile(body).getFirst();
        String line = body.lines().filter(l -> l.contains("missing")).findFirst().orElseThrow();
        assertEquals(PREFIX.lines().count() + 3, d.getLineNumber());
        assertEquals(line.indexOf("missing") + 1, d.getColumnNumber());
    }

    @Test
    void guardAfterValueDefinitionIsRejected() throws Exception {
        assertError("""
                List<Integer> r = forj {
                    x <- List.of(1, 2);
                    var y = x * 2;
                    guard(y > 2);
                } yield y;
                return r;
                """, "guard(...) must directly follow a generator");
    }

    @Test
    void arrowInsideNestedStatementIsRejected() throws Exception {
        assertError("""
                List<Integer> r = forj {
                    x <- List.of(1, 2);
                    if (x > 1) {
                        y <- List.of(3);
                    }
                } yield x;
                return r;
                """, "must be a top-level statement of a forj");
    }

    @Test
    void arrowOutsideForjIsRejected() throws Exception {
        assertError("""
                x <- List.of(1, 2);
                return x;
                """, "must be a top-level statement of a forj");
    }

    @Test
    void yieldInsideTheBlockIsRejected() throws Exception {
        assertError("""
                List<Integer> r = forj {
                    x <- List.of(1, 2);
                    if (x > 5) {
                        yield x;
                    }
                    y <- List.of(3);
                } yield x + y;
                return r;
                """, "yield goes after the block");
    }

    @Test
    void missingYieldIsRejected() throws Exception {
        assertEquals(List.of("forj: forj { ... } must be followed by yield <expr>"), errors("""
                List<Integer> r = forj {
                    x <- List.of(1, 2);
                };
                return r;
                """));
    }

    @Test
    void mixingMonadsIsATypeError() throws Exception {
        // As in Scala, a List generator cannot be followed by an Optional one.
        assertTrue(!errors("""
                List<Integer> r = forj {
                    x <- List.of(1, 2);
                    y <- Optional.of(3);
                } yield x + y;
                return r;
                """).isEmpty());
    }

    @Test
    void typeWithoutInstanceIsATypeError() throws Exception {
        assertError("""
                java.util.concurrent.atomic.AtomicReference<Integer> f = null;
                Object r = forj {
                    x <- f;
                } yield x;
                return r;
                """, "forjMap");
    }
}
