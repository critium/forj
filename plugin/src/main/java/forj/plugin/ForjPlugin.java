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
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import javax.tools.JavaFileObject;

/**
 * javac plugin for {@code forj} comprehensions. Two stages:
 * <ol>
 *   <li>before parsing, {@link SourceRewriter} turns {@code forj { x <- e; } yield f(x)}
 *       into {@code forj (() -> { x =  e; yield f(x); })}, which javac can parse;</li>
 *   <li>after parsing, before name resolution, {@link Desugarer} rewrites each
 *       {@code forj} block into flatMap/map/filter calls.</li>
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
                }
            }
        });
    }
}
