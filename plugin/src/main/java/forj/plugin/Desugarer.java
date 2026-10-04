package forj.plugin;

import com.sun.source.util.Trees;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCAssign;
import com.sun.tools.javac.tree.JCTree.JCBlock;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCExpressionStatement;
import com.sun.tools.javac.tree.JCTree.JCIdent;
import com.sun.tools.javac.tree.JCTree.JCImport;
import com.sun.tools.javac.tree.JCTree.JCLambda;
import com.sun.tools.javac.tree.JCTree.JCMethodInvocation;
import com.sun.tools.javac.tree.JCTree.JCPackageDecl;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.tree.JCTree.JCSwitchExpression;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl.DeclKind;
import com.sun.tools.javac.tree.JCTree.JCYield;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.tree.TreeScanner;
import com.sun.tools.javac.tree.TreeTranslator;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.Names;
import com.sun.tools.javac.util.Position;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.tools.Diagnostic;

/**
 * Rewrites, purely syntactically (the same way scalac does):
 *
 * <pre>{@code
 * forj {                           forj.For.run(() -> {
 *     x <- xs;                         return forjFlatMap(forjFilter(xs, x -> p(x)), x -> {
 *     guard(p(x));                         var z = f(x);
 *     var z = f(x);          ==>           return forjMap(ys(z), y -> {
 *     y <- ys(z);                              return g(x, y);
 * } yield g(x, y);                         });
 *                                      });
 *                                  });
 * }</pre>
 *
 * Statements before a generator stay where they are; every generator but the last becomes
 * {@code forjFlatMap}, the last becomes {@code forjMap}, and the statements after it (ending
 * in {@code yield}) become the map body. Guards filter the generator directly above them.
 *
 * <p>It sees the output of {@link SourceRewriter}: {@code forj (() -> { ... yield e; })},
 * with generators as {@code x = e} assignments. The rewrite result says which assignments
 * were written as {@code <-} and which yields it generated.
 */
final class Desugarer extends TreeTranslator {

    private final TreeMaker make;
    private final Names names;
    private final Trees trees;
    private final JCCompilationUnit unit;
    private final Map<Integer, Boolean> arrows;
    private final Set<Integer> yields;
    private final Set<Integer> missingYield;
    private final boolean debug;

    private final Name forjPkg, forClass, run, forjBlock, guard, flatMap, map, filter;

    private boolean rewrote;
    private int fresh;

    Desugarer(Context context, Trees trees, JCCompilationUnit unit, SourceRewriter.Result rewrite, boolean debug) {
        this.make = TreeMaker.instance(context);
        this.names = Names.instance(context);
        this.trees = trees;
        this.unit = unit;
        this.arrows = new HashMap<>(rewrite.arrows());
        this.yields = rewrite.yields();
        this.missingYield = rewrite.missingYield();
        this.debug = debug;
        this.forjPkg = names.fromString("forj");
        this.forClass = names.fromString("For");
        this.run = names.fromString("run");
        this.forjBlock = names.fromString("forj");
        this.guard = names.fromString("guard");
        this.flatMap = names.fromString("forjFlatMap");
        this.map = names.fromString("forjMap");
        this.filter = names.fromString("forjFilter");
    }

    void run() {
        unit.defs = translate(unit.defs);
        rejectUnusedArrows();
        if (rewrote) {
            ensureStaticImport();
        }
    }

    @Override
    public void visitApply(JCMethodInvocation tree) {
        super.visitApply(tree); // inner comprehensions first
        if (isCall(tree, forjBlock)) {
            rewriteForj(tree);
        }
        result = tree;
    }

    // ------------------------------------------------------------- rewriting

    private void rewriteForj(JCMethodInvocation call) {
        if (call.args.size() != 1
                || !(call.args.head instanceof JCLambda lambda)
                || !lambda.params.isEmpty()
                || !(lambda.body instanceof JCBlock body)) {
            error(call, "write comprehensions as forj { ... } yield expr");
            return;
        }
        if (missingYield.contains(call.meth.pos)) {
            error(call.meth, "forj { ... } must be followed by yield <expr>");
            forgetArrows(body); // already reported; don't also flag them as misplaced
            return;
        }
        if (indexOfGenerator(body.stats) < 0) {
            error(call.meth, "comprehension has no generator (x <- ...)");
            return;
        }
        body.stats = desugar(body.stats);
        rejectStrayMarkers(body);

        make.at(call.meth.pos);
        call.meth = make.Select(make.Select(make.Ident(forjPkg), forClass), run);
        rewrote = true;

        if (debug) {
            long line = unit.getLineMap().getLineNumber(call.pos);
            System.err.println("[forj] " + unit.getSourceFile().getName() + ":" + line + "\n" + call);
        }
    }

    private List<JCStatement> desugar(List<JCStatement> stats) {
        int i = indexOfGenerator(stats);
        if (i < 0) {
            return stats;
        }
        ListBuffer<JCStatement> prefix = new ListBuffer<>();
        List<JCStatement> rest = stats;
        for (int k = 0; k < i; k++) {
            prefix.append(rest.head);
            rest = rest.tail;
        }

        JCStatement generator = rest.head;
        rest = rest.tail;
        JCAssign arrow = (JCAssign) ((JCExpressionStatement) generator).expr;
        boolean anonymous = arrows.remove(arrow.pos);
        JCIdent binder = (JCIdent) arrow.lhs;
        Name var = anonymous ? names.fromString("forj$" + fresh++) : binder.name;
        JCExpression source = arrow.rhs;

        make.at(generator.pos);
        while (rest.nonEmpty() && isGuard(rest.head)) {
            JCExpression cond = ((JCMethodInvocation) ((JCExpressionStatement) rest.head).expr).args.head;
            source = call(filter, source, make.Lambda(List.of(param(var, binder, anonymous)), cond));
            rest = rest.tail;
        }

        if (rest.isEmpty()) {
            error(generator, "forj { ... } must be followed by yield <expr>");
            return stats;
        }

        boolean last = indexOfGenerator(rest) < 0;
        List<JCStatement> inner = last ? yieldsToReturns(rest) : desugar(rest);
        make.at(generator.pos);
        JCLambda continuation = make.Lambda(List.of(param(var, binder, anonymous)), make.Block(0, inner));
        prefix.append(make.Return(call(last ? map : flatMap, source, continuation)));
        return prefix.toList();
    }

    /** The final map lambda returns the expression from {@code } yield e}. */
    private List<JCStatement> yieldsToReturns(List<JCStatement> stats) {
        ListBuffer<JCStatement> out = new ListBuffer<>();
        for (JCStatement s : stats) {
            out.append(s instanceof JCYield y && yields.contains(y.pos) ? make.at(y.pos).Return(y.value) : s);
        }
        return out.toList();
    }

    private JCMethodInvocation call(Name method, JCExpression monad, JCLambda fn) {
        // Unqualified on purpose: overload resolution across every `import static X.*`
        // in the file selects the monad instance, which is how third parties plug in.
        return make.Apply(List.nil(), make.Ident(method), List.of(monad, fn));
    }

    /**
     * The binder of {@code x <- e} becomes a lambda parameter positioned on the {@code x}, so
     * tools see the generator as the variable's definition (go-to-definition lands there).
     * Other generated nodes get a start position for error messages but no end position,
     * which keeps them out of semanticdb's index.
     */
    private JCVariableDecl param(Name name, JCIdent binder, boolean anonymous) {
        JCVariableDecl p = make.VarDef(make.Modifiers(Flags.PARAMETER), name, null, null, DeclKind.IMPLICIT);
        if (!anonymous) {
            p.pos = binder.pos;
            p.endpos = binder.endpos;
        }
        return p;
    }

    /** The rewritten code calls forjFlatMap etc. unqualified; make sure the JDK instances are visible. */
    private void ensureStaticImport() {
        for (JCTree def : unit.defs) {
            if (def instanceof JCImport imp && imp.staticImport
                    && imp.qualid.toString().equals("forj.For.*")) {
                return;
            }
        }
        make.at(Position.NOPOS); // generated: no source range, so indexers like semanticdb skip it
        JCImport imp = make.Import(
                make.Select(make.Select(make.Ident(forjPkg), forClass), names.asterisk), true);
        ListBuffer<JCTree> defs = new ListBuffer<>();
        boolean added = false;
        for (JCTree def : unit.defs) {
            defs.append(def);
            if (def instanceof JCPackageDecl) {
                defs.append(imp);
                added = true;
            }
        }
        unit.defs = added ? defs.toList() : unit.defs.prepend(imp);
    }

    // ------------------------------------------------------------ recognizers

    private int indexOfGenerator(List<JCStatement> stats) {
        int i = 0;
        for (JCStatement s : stats) {
            if (isGenerator(s)) {
                return i;
            }
            i++;
        }
        return -1;
    }

    private boolean isGenerator(JCStatement s) {
        return s instanceof JCExpressionStatement es
                && es.expr instanceof JCAssign a && a.lhs instanceof JCIdent && arrows.containsKey(a.pos);
    }

    private boolean isGuard(JCStatement s) {
        return s instanceof JCExpressionStatement es
                && es.expr instanceof JCMethodInvocation inv && isCall(inv, guard) && inv.args.size() == 1;
    }

    /** {@code name(..)}: forj and guard are only ever written bare. */
    private boolean isCall(JCMethodInvocation inv, Name name) {
        return inv.meth instanceof JCIdent id && id.name == name;
    }

    /** guard/yield left over after desugaring were used somewhere the rewrite can't reach. */
    private void rejectStrayMarkers(JCBlock body) {
        new TreeScanner() {
            @Override
            public void visitApply(JCMethodInvocation inv) {
                if (isCall(inv, guard)) {
                    error(inv, "guard(...) must directly follow a generator (x <- ...) or another guard(...)");
                }
                super.visitApply(inv);
            }

            @Override
            public void visitYield(JCYield tree) {
                error(tree, "yield goes after the block: forj { ... } yield expr");
            }

            @Override
            public void visitSwitchExpression(JCSwitchExpression tree) {
                scan(tree.selector); // yields inside belong to the switch
            }

            @Override
            public void visitClassDef(JCClassDecl tree) {}
        }.scan(body);
    }

    private void forgetArrows(JCTree tree) {
        new TreeScanner() {
            @Override
            public void visitAssign(JCAssign assign) {
                arrows.remove(assign.pos);
                super.visitAssign(assign);
            }
        }.scan(tree);
    }

    /** Every generator is consumed by a forj block; any left are in the wrong place. */
    private void rejectUnusedArrows() {
        if (arrows.isEmpty()) {
            return;
        }
        new TreeScanner() {
            @Override
            public void visitAssign(JCAssign tree) {
                if (arrows.containsKey(tree.pos)) {
                    error(tree, "x <- ... must be a top-level statement of a forj { ... } block");
                }
                super.visitAssign(tree);
            }
        }.scan(unit);
    }

    private void error(JCTree at, String message) {
        trees.printMessage(Diagnostic.Kind.ERROR, "forj: " + message, at, unit);
    }
}
