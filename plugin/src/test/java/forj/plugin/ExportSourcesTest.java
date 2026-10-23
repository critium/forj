package forj.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** ExportSources: sources for javadoc, as the plugin's text stage rewrites them. */
class ExportSourcesTest {

    @TempDir
    Path dir;

    private String export(String path, String source, String... more) throws Exception {
        Path src = dir.resolve("src");
        Files.createDirectories(src.resolve(path).getParent());
        Files.writeString(src.resolve(path), source);
        for (int i = 0; i + 1 < more.length; i += 2) {
            Files.createDirectories(src.resolve(more[i]).getParent());
            Files.writeString(src.resolve(more[i]), more[i + 1]);
        }
        StringWriter errors = new StringWriter();
        ExportSources.export(dir.resolve("out"), List.of(src), new PrintWriter(errors));
        assertEquals("", errors.toString());
        return Files.readString(dir.resolve("out").resolve(path));
    }

    @Test
    void declarationsBecomeWhatPlainJavaCallersSee() throws Exception {
        String out = export("lib/Json.java", """
                package lib;
                public final class Json {
                    public static <A> String encode(A value)(using Codec<A> codec) { return codec.write(value); }
                    public static <F<_>> F<String> pure(String s)(using forj.typeclass.Applicative<F> ap) { return ap.pure(s); }
                }
                """);
        assertTrue(out.contains("encode(A value, @forj.Using Codec<A> codec)"), out);
        assertTrue(out.contains("forj.Kind<F, String> pure(String s, @forj.Using forj.typeclass.Applicative<F> ap)"), out);
    }

    @Test
    void linksToUsingMethodsGetTheUsingTypes() throws Exception {
        String out = export("lib/Json.java", """
                package lib;
                import java.io.OutputStream;
                import other.Codec;
                /**
                 * {@link #encode(Object, OutputStream)}, {@linkplain #encode(Object) the short one},
                 * {@link #encode(Object, OutputStream, Codec)} (already complete), {@link #plain(int)}.
                 * @see Json#encode(Object)
                 */
                public final class Json {
                    public static <A> void encode(A value, OutputStream out)(using Codec<A> codec) {}
                    public static <A> String encode(A value)(using Codec<A> codec) { return ""; }
                    public static void plain(int n) {}
                }
                """, "lib/Other.java", """
                package lib;
                /** {@link Json#encode(Object)} from another file. */
                public final class Other {}
                """);
        assertTrue(out.contains("{@link #encode(Object, OutputStream, other.Codec)}"), out);
        assertTrue(out.contains("{@linkplain #encode(Object, other.Codec) the short one}"), out);
        assertTrue(out.contains("{@link #encode(Object, OutputStream, Codec)} (already complete)"), out);
        assertTrue(out.contains("{@link #plain(int)}"), out);
        assertTrue(out.contains("@see Json#encode(Object, other.Codec)"), out);
        String other = Files.readString(dir.resolve("out/lib/Other.java"));
        assertTrue(other.contains("{@link Json#encode(Object, other.Codec)}"), other);
    }

    @Test
    void filesWithoutForjSyntaxAreCopiedAsTheyAre() throws Exception {
        String source = """
                package lib;
                /** {@link #f(int)} */
                public final class Plain { public static void f(int n) {} }
                """;
        assertEquals(source, export("lib/Plain.java", source));
    }
}
