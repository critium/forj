package forj.plugin;

import com.sun.tools.javac.parser.ScannerFactory;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Log;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Writes sources the way the plugin's first stage rewrites them, for tools that parse
 * {@code .java} files without the plugin: {@code javadoc} above all. The output is what javac
 * parses with {@code -Xplugin:Forj} before desugaring: {@code F<A>} is
 * {@code forj.Kind<F, A>}, {@code using} clauses are {@code @forj.Using} parameters,
 * {@code given} is {@code @forj.Given}, comprehensions are lambdas. Declarations are exactly
 * what plain Java callers see, and method bodies parse (javadoc doesn't type check them).
 *
 * <pre>{@code
 * java --add-exports=jdk.compiler/com.sun.tools.javac.{parser,util}=ALL-UNNAMED \
 *      -cp forj-plugin.jar forj.plugin.ExportSources <out dir> <source root>...
 * }</pre>
 *
 * Files that use no forj syntax are copied as they are; other files under the roots are
 * ignored. A varargs parameter followed by a using clause stays an array: moving it last is
 * the Desugarer's job, which runs after parsing.
 */
public final class ExportSources {

    private ExportSources() {}

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("usage: ExportSources <out dir> <source root>...");
            System.exit(2);
        }
        Path out = Path.of(args[0]);
        List<Path> roots = Stream.of(args).skip(1).map(Path::of).filter(Files::isDirectory).toList();
        StringWriter errors = new StringWriter();
        int count = export(out, roots, new PrintWriter(errors));
        if (!errors.toString().isEmpty()) {
            System.err.print(errors);
        }
        System.out.println("forj: exported " + count + " sources to " + out);
    }

    /** Rewrites every {@code .java} file under {@code roots} into {@code out}; returns how many. */
    static int export(Path out, List<Path> roots, PrintWriter errors) throws IOException {
        Context context = new Context();
        // the real compile reports lexical errors; here they would only repeat them
        context.put(Log.errKey, errors);
        ScannerFactory scanners = ScannerFactory.instance(context);
        Log log = Log.instance(context);
        int count = 0;
        for (Path root : roots) {
            List<Path> sources;
            try (Stream<Path> files = Files.walk(root)) {
                sources = files.filter(f -> f.toString().endsWith(".java") && Files.isRegularFile(f)).toList();
            }
            for (Path source : sources) {
                String text = Files.readString(source);
                SourceRewriter.Result rewritten = SourceRewriter.rewrite(text, scanners, log);
                Path target = out.resolve(root.relativize(source).toString());
                Files.createDirectories(target.getParent());
                Files.writeString(target, rewritten == null ? text : new String(rewritten.source()));
                count++;
            }
        }
        return count;
    }
}
