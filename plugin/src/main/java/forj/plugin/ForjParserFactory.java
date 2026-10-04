package forj.plugin;

import com.sun.tools.javac.main.JavaCompiler;
import com.sun.tools.javac.parser.JavacParser;
import com.sun.tools.javac.parser.ParserFactory;
import com.sun.tools.javac.parser.ScannerFactory;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.tree.TreeScanner;
import com.sun.tools.javac.util.AbstractLog;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.DiagnosticSource;
import com.sun.tools.javac.util.Log;
import com.sun.tools.javac.util.Position;
import java.util.Map;
import javax.tools.JavaFileObject;

/**
 * javac's parser factory with forj syntax support: hands each file to {@link SourceRewriter}
 * before the real parser sees it, recording what was rewritten per file.
 */
final class ForjParserFactory extends ParserFactory {

    private final ScannerFactory scanners;
    private final Log log;
    private final Map<JavaFileObject, SourceRewriter.Result> rewrites;

    private ForjParserFactory(Context context, Map<JavaFileObject, SourceRewriter.Result> rewrites) {
        super(context);
        this.scanners = ScannerFactory.instance(context);
        this.log = Log.instance(context);
        this.rewrites = rewrites;
    }

    /**
     * Replaces the compiler's parser factory. Plugins are initialized after the compiler
     * already holds one, so this reaches into javac (hence the --add-opens flags).
     */
    static void install(Context context, Map<JavaFileObject, SourceRewriter.Result> rewrites) {
        try {
            var table = Context.class.getDeclaredField("ht");
            table.setAccessible(true);
            ((Map<?, ?>) table.get(context)).remove(parserFactoryKey);

            var factory = new ForjParserFactory(context, rewrites);

            var field = JavaCompiler.class.getDeclaredField("parserFactory");
            field.setAccessible(true);
            field.set(JavaCompiler.instance(context), factory);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException(
                    "forj: cannot install the forj parser on this JDK; is javac running with "
                            + "--add-opens=jdk.compiler/com.sun.tools.javac.{util,main}=ALL-UNNAMED?", e);
        }
    }

    @Override
    public JavacParser newParser(CharSequence input, boolean keepDocComments, boolean keepEndPos, boolean keepLineMap) {
        var result = SourceRewriter.rewrite(input, scanners, log);
        if (result == null) {
            return super.newParser(input, keepDocComments, keepEndPos, keepLineMap);
        }
        JavaFileObject file = log.currentSourceFile();
        rewrites.put(file, result);
        reportAgainst(file, result.source());
        return super.newParser(java.nio.CharBuffer.wrap(result.source()), keepDocComments, keepEndPos, keepLineMap);
    }

    /**
     * While parsing, positions index the rewritten text, so syntax errors must resolve lines
     * and columns against it too, not against the file on disk.
     */
    private void reportAgainst(JavaFileObject file, char[] source) {
        sourceMap(log).put(file, new RewrittenSource(file, log, source));
        log.useSource(file); // the parser is about to report against the current source
    }

    /**
     * Moves a freshly parsed tree back to the coordinates of the file on disk, so everything
     * after parsing (diagnostics, debug line tables, the semanticdb index Metals reads) sees
     * positions that match the original source.
     */
    static void restoreOriginalPositions(JCCompilationUnit unit, SourceRewriter.Result rewrite, Log log) {
        var positions = rewrite.positions();
        new TreeScanner() {
            @Override
            public void scan(JCTree tree) {
                if (tree != null) {
                    tree.pos = positions.toOriginal(tree.pos);
                    tree.endpos = positions.toOriginal(tree.endpos);
                }
                super.scan(tree);
            }
        }.scan(unit);
        char[] original = rewrite.original();
        unit.lineMap = Position.makeLineMap(original, original.length, false);
        sourceMap(log).remove(unit.getSourceFile()); // back to reading the file itself
    }

    @SuppressWarnings("unchecked")
    private static Map<JavaFileObject, DiagnosticSource> sourceMap(Log log) {
        try {
            var field = AbstractLog.class.getDeclaredField("sourceMap");
            field.setAccessible(true);
            return (Map<JavaFileObject, DiagnosticSource>) field.get(log);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("forj: cannot redirect diagnostics on this JDK", e);
        }
    }

    private static final class RewrittenSource extends DiagnosticSource {
        private final char[] source;

        RewrittenSource(JavaFileObject file, AbstractLog log, char[] source) {
            this.source = source;
            super(file, log);
        }

        @Override
        protected char[] initBuf(JavaFileObject file) {
            bufLen = source.length;
            return source;
        }
    }
}
