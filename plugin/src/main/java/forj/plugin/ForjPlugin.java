package forj.plugin;

import com.sun.source.util.JavacTask;
import com.sun.source.util.Plugin;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.Trees;
import com.sun.tools.javac.api.BasicJavacTask;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Log;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import javax.tools.JavaFileObject;

/**
 * javac plugin for {@code forj} comprehensions. Two stages:
 * <ol>
 *   <li>before parsing, {@link SourceRewriter} turns {@code forj { x <- e; } yield f(x)}
 *       into {@code forj (() -> { x =  e; yield f(x); })}, which javac can parse;</li>
 *   <li>after parsing, before name resolution, {@link Desugarer} rewrites each
 *       {@code forj} block into flatMap/map/filter calls;</li>
 *   <li>before javac type checks each class, {@link ImplicitResolver} passes the
 *       {@code given} instances that calls leave out of their {@code using} parameters.</li>
 * </ol>
 *
 * <p>Enable with {@code -Xplugin:Forj}, or {@code -Xplugin:"Forj debug"} to print each
 * rewritten comprehension to stderr.
 */
public final class ForjPlugin implements Plugin {

    @Override
    public String getName() {
        return "Forj";
    }

    @Override
    public void init(JavacTask task, String... args) {
        boolean debug = Arrays.asList(args).contains("debug");
        Context context = ((BasicJavacTask) task).getContext();
        Trees trees = Trees.instance(task);
        Log log = Log.instance(context);
        Map<JavaFileObject, SourceRewriter.Result> rewrites = new HashMap<>();
        ForjParserFactory.install(context, rewrites);
        if (Boolean.getBoolean("forj.trace")) {
            // where does javac report each error from?
            log.new DiagnosticHandler() {
                @Override
                protected void reportReady(com.sun.tools.javac.util.JCDiagnostic d) {
                    if (d.getKind() == javax.tools.Diagnostic.Kind.ERROR) {
                        new Throwable("[forj.trace] error: " + d.getMessage(null)).printStackTrace();
                    }
                    prev.report(d);
                }
            };
        }

        List<JCCompilationUnit> parsed = new ArrayList<>();
        ImplicitResolver[] implicits = {null};

        task.addTaskListener(new TaskListener() {
            @Override
            public void finished(TaskEvent e) {
                if (e.getKind() == TaskEvent.Kind.PARSE) {
                    var unit = (JCCompilationUnit) e.getCompilationUnit();
                    var rewrite = rewrites.remove(e.getSourceFile());
                    if (rewrite != null) {
                        ForjParserFactory.restoreOriginalPositions(unit, rewrite, log);
                        new Desugarer(context, trees, unit, rewrite.inOriginalPositions(), debug).run();
                    }
                    parsed.add(unit);
                }
            }

            @Override
            public void started(TaskEvent e) {
                if (e.getKind() != TaskEvent.Kind.ANALYZE || e.getTypeElement() == null) {
                    return;
                }
                if (implicits[0] == null) {
                    var given = (ClassSymbol) task.getElements().getTypeElement("forj.Given");
                    var using = (ClassSymbol) task.getElements().getTypeElement("forj.Using");
                    if (given == null || using == null) {
                        return; // forj's core isn't on the classpath: nothing can be given
                    }
                    implicits[0] = new ImplicitResolver(context, trees, task.getElements(), given, using);
                    parsed.forEach(implicits[0]::index);
                }
                implicits[0].resolve((JCCompilationUnit) e.getCompilationUnit(), e.getTypeElement());
            }
        });
    }
}
