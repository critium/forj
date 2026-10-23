package forj.plugin;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import com.sun.tools.javac.api.JavacScope;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Kinds.Kind;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symbol.MethodSymbol;
import com.sun.tools.javac.code.Symbol.VarSymbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Type.ArrayType;
import com.sun.tools.javac.code.Type.TypeVar;
import com.sun.tools.javac.code.Type.WildcardType;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.comp.ArgumentAttr;
import com.sun.tools.javac.comp.Attr;
import com.sun.tools.javac.comp.AttrContext;
import com.sun.tools.javac.comp.Env;
import com.sun.tools.javac.comp.Resolve;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCFieldAccess;
import com.sun.tools.javac.tree.JCTree.JCImport;
import com.sun.tools.javac.tree.JCTree.JCAssign;
import com.sun.tools.javac.tree.JCTree.JCBlock;
import com.sun.tools.javac.tree.JCTree.JCLambda;
import com.sun.tools.javac.tree.JCTree.JCReturn;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCMethodInvocation;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.tree.TreeCopier;
import com.sun.tools.javac.tree.TreeInfo;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Log;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.Names;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;

/**
 * Compile-time context parameters and type classes: fills in {@code using} arguments that a
 * call leaves out with the matching {@code given}, before javac type checks the class.
 *
 * <pre>{@code
 * given Ordering<Integer> integer = Integer::compare;
 * given <A> Ordering<List<A>> list(using Ordering<A> elem) { ... }
 * static <A> A max(List<A> xs) using Ordering<A> ord { ... }
 *
 * max(List.of(List.of(3), List.of(1)))   ==>   max(..., Orderings.list(Orderings.integer))
 * }</pre>
 *
 * For each such call it type checks copies of the arguments (javac's own speculative
 * attribution), infers the method's type parameters from them, and searches for a given of
 * each required type, recursively for givens that themselves take {@code using} parameters.
 * Search order, first non-empty level wins, more than one match there is an error:
 * <ol>
 *   <li>local {@code given} variables declared earlier in the enclosing blocks;</li>
 *   <li>{@code using} parameters of the enclosing method;</li>
 *   <li>givens declared in the enclosing classes (and their supertypes);</li>
 *   <li>givens imported with {@code import static};</li>
 *   <li>givens in the type class's own class and in the classes of its type arguments
 *       (Scala's implicit scope, the closest Java has to companion objects);</li>
 *   <li>{@code forj.Instances}: instances for JDK types.</li>
 * </ol>
 * The inserted arguments are ordinary Java, so javac type checks them like any other code.
 *
 * <p>Each pass visits every call ({@link #pass}) in a fixed order: unshadowing generated names,
 * the comprehension plumbing and narrowing ({@link Lifting}), extension calls
 * ({@link Extensions}), then {@code using} arguments ({@link CallSite#complete}). Before the
 * passes, {@link DerivationSpecializer} rewrites derived givens.
 */
final class ImplicitResolver {

    private static final int MAX_DEPTH = 16;
    private static final boolean TRACE = Boolean.getBoolean("forj.trace");

    static void trace(String message) {
        if (TRACE) {
            System.err.println("[forj.trace] " + message);
        }
    }

    private final Trees trees;
    final Attr attr;
    private final ArgumentAttr argumentAttr;
    private final java.lang.reflect.Field argumentCache;
    final Types types;
    final Resolve rs;
    final Log log;
    final TreeMaker make;
    final Names names;
    private final ClassSymbol givenAnnotation;
    private final ClassSymbol usingAnnotation;
    private final javax.lang.model.util.Elements elements;
    final ClassSymbol lowerAnnotation;
    final ClassSymbol kindSymbol;
    final ClassSymbol forClass;
    private final ClassSymbol defaultInstances;
    private final ClassSymbol extensionAnnotation;
    final ClassSymbol defaultSyntax;
    private final Name forjLower;
    final Name forjLift;

    enum Outcome { SKIPPED, RESOLVED, PENDING }

    /** Names of methods known to take {@code using} parameters; calls to anything else are skipped quickly. */
    private final Set<Name> usingMethodNames = new java.util.HashSet<>();
    /** Names of methods known to return a {@code Kind}: their results get narrowed where assigned or returned. */
    private final Set<Name> kindMethodNames = new java.util.HashSet<>();
    /** Names of extension methods; {@code x.name(...)} is only looked at for these. */
    private final Set<Name> extensionNames = new java.util.HashSet<>();
    private final Set<Name> indexedPackages = new java.util.HashSet<>();

    // opt-in features (forj.Language), switched on per file by `import static forj.Language.x;`
    static final String LIFT_RESULTS = "liftResults";
    static final String LIFT_RECEIVERS = "liftReceivers";
    private static final String LIFT_ARGUMENTS = "liftArguments";
    private final Map<JCCompilationUnit, Set<String>> features = new HashMap<>();
    private final Map<JCCompilationUnit, Set<Name>> variableNames = new HashMap<>();

    /** Every variable, parameter and field name declared in a file: the names that could shadow a package. */
    private Set<Name> variableNames(JCCompilationUnit unit) {
        return variableNames.computeIfAbsent(unit, u -> {
            Set<Name> found = new java.util.HashSet<>();
            new com.sun.tools.javac.tree.TreeScanner() {
                @Override
                public void visitVarDef(JCVariableDecl tree) {
                    found.add(tree.name);
                    super.visitVarDef(tree);
                }
            }.scan(u);
            return found;
        });
    }

    /** The forj.Language features a file imports; {@code lifting} and {@code *} mean all three. */
    private Set<String> features(JCCompilationUnit unit) {
        return features.computeIfAbsent(unit, u -> {
            Set<String> on = new java.util.HashSet<>();
            for (JCTree def : u.defs) {
                if (def instanceof JCImport imp && imp.staticImport
                        && imp.qualid.selected.toString().equals("forj.Language")) {
                    String feature = imp.qualid.name.toString();
                    if (feature.equals("*") || feature.equals("lifting")) {
                        on.addAll(List.of(LIFT_RESULTS, LIFT_RECEIVERS, LIFT_ARGUMENTS));
                    } else {
                        on.add(feature);
                    }
                }
            }
            return on;
        });
    }

    /** Givens the deriver added, not yet looked at: see {@link DerivationSpecializer}. */
    final List<Deriver.Derivation> derivations;
    final com.sun.tools.javac.comp.Enter enter;

    /** The {@code forj} identifiers the desugarer generated ({@code forj.For.forjFlatMap}); see {@code unshadow}. */
    private final Set<JCTree.JCIdent> generatedQualifiers;

    private final Type objectType;

    ImplicitResolver(Context context, Trees trees, javax.lang.model.util.Elements elements,
                     ClassSymbol givenAnnotation, ClassSymbol usingAnnotation, Set<JCTree.JCIdent> generatedQualifiers,
                     List<Deriver.Derivation> derivations) {
        this.generatedQualifiers = generatedQualifiers;
        this.derivations = derivations;
        this.enter = com.sun.tools.javac.comp.Enter.instance(context);
        this.elements = elements;
        this.trees = trees;
        this.attr = Attr.instance(context);
        this.argumentAttr = ArgumentAttr.instance(context);
        try {
            this.argumentCache = ArgumentAttr.class.getDeclaredField("argumentTypeCache");
            argumentCache.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("forj: needs --add-opens=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED", e);
        }
        this.types = Types.instance(context);
        this.objectType = com.sun.tools.javac.code.Symtab.instance(context).objectType;
        this.rs = Resolve.instance(context);
        this.log = Log.instance(context);
        this.make = TreeMaker.instance(context);
        this.names = Names.instance(context);
        this.givenAnnotation = givenAnnotation;
        this.usingAnnotation = usingAnnotation;
        this.lowerAnnotation = (ClassSymbol) elements.getTypeElement("forj.Lower");
        this.kindSymbol = (ClassSymbol) elements.getTypeElement("forj.Kind");
        this.forClass = (ClassSymbol) elements.getTypeElement("forj.For");
        this.defaultInstances = (ClassSymbol) elements.getTypeElement("forj.Instances");
        this.extensionAnnotation = (ClassSymbol) elements.getTypeElement("forj.Extension");
        this.defaultSyntax = (ClassSymbol) elements.getTypeElement("forj.Syntax");
        this.forjLower = names.fromString("forjLower");
        this.forjLift = names.fromString("forjLift");
        for (String name : List.of("forj.For", "forj.Implicits", "forj.Syntax")) {
            if (elements.getTypeElement(name) instanceof ClassSymbol c) {
                indexMembers(c);
            }
        }
    }

    /** Records methods in a parsed file that declare {@code @forj.Using} parameters. */
    void index(JCCompilationUnit unit) {
        new com.sun.tools.javac.tree.TreeScanner() {
            @Override
            public void visitMethodDef(JCMethodDecl tree) {
                if (tree.restype != null && tree.restype.toString().matches("(forj\\.)?Kind<.*")) {
                    kindMethodNames.add(tree.name);
                }
                if (tree.mods.annotations.stream().anyMatch(a -> a.annotationType.toString().endsWith("Extension"))) {
                    extensionNames.add(tree.name);
                }
                for (JCVariableDecl p : tree.params) {
                    if (p.mods.annotations.stream().anyMatch(a -> a.annotationType.toString().endsWith("Using"))) {
                        usingMethodNames.add(tree.name);
                    }
                }
                super.visitMethodDef(tree);
            }
        }.scan(unit);
    }

    /** Fills in missing {@code using} arguments in one top-level class, just before javac attributes it. */
    void resolve(JCCompilationUnit unit, TypeElement type) {
        TreePath classPath = trees.getPath(type);
        if (classPath == null) {
            return;
        }
        indexImports(unit);
        new DerivationSpecializer(this).run();
        // A call can only be resolved once its arguments' types are known, and inside a lambda
        // those can depend on resolving the enclosing call first (nested comprehensions), and
        // resolving a step lets the next pass write out its type arguments. So: passes until
        // nothing changes, then a last pass that reports what is still missing.
        while (true) {
            int[] counts = pass(unit, classPath, false);
            if (counts[0] == 0) {           // no progress: report whatever is still missing
                if (counts[1] > 0) {
                    pass(unit, classPath, true);
                }
                break;
            }
        }
        if (TRACE) {
            trace("resolved class:\n" + classPath.getLeaf());
        }
    }

    /** One pass over a class; returns {resolved, pending}. */
    private int[] pass(JCCompilationUnit unit, TreePath classPath, boolean report) {
        int[] counts = {0, 0};
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                super.visitMethodInvocation(node, unused); // innermost calls first
                var call = (JCMethodInvocation) node;
                if (call.meth instanceof JCFieldAccess outer && outer.selected instanceof JCFieldAccess inner
                        && inner.selected instanceof JCTree.JCIdent root && generatedQualifiers.remove(root)
                        && variableNames(unit).contains(root.name)) {
                    new CallSite(unit, getCurrentPath(), call).unshadow(outer, inner);
                }
                Name name = TreeInfo.name(call.meth);
                Outcome outcome = Outcome.SKIPPED;
                if (name == forjLower && call.args.size() == 1) {
                    outcome = new CallSite(unit, getCurrentPath(), call).lifting.lower();
                } else if (name == forjLift && call.args.size() == 1 && call.meth instanceof com.sun.tools.javac.tree.JCTree.JCIdent) {
                    outcome = new CallSite(unit, getCurrentPath(), call).lifting.liftKind();
                } else if (isUnpinnedStep(call, name)) {
                    outcome = new CallSite(unit, getCurrentPath(), call).pinTypeArguments();
                } else {
                    if (extensionNames.contains(name) && call.meth instanceof JCFieldAccess) {
                        outcome = new Extensions(ImplicitResolver.this, new CallSite(unit, getCurrentPath(), call)).resolve(report);
                    }
                    if (outcome == Outcome.SKIPPED && usingMethodNames.contains(name)) {
                        outcome = new CallSite(unit, getCurrentPath(), call).complete(report);
                    }
                }
                if (outcome != Outcome.PENDING && kindMethodNames.contains(name) && narrowable(getCurrentPath())
                        && new CallSite(unit, getCurrentPath(), call).lifting.narrow()) {
                    outcome = Outcome.RESOLVED;
                }
                if (outcome != Outcome.SKIPPED || TRACE) {
                    trace((report ? "report " : "pass ") + outcome + " " + call);
                }
                if (outcome == Outcome.RESOLVED) {
                    counts[0]++;
                } else if (outcome == Outcome.PENDING) {
                    counts[1]++;
                }
                return null;
            }
        }.scan(classPath, null);
        return counts;
    }

    /**
     * Methods with using-parameters in imported classes: types imported by name, and the
     * classes behind {@code import static}, single or on demand.
     */
    private void indexImports(JCCompilationUnit unit) {
        if (unit.packge != null && indexedPackages.add(unit.packge.fullname)) {
            // same-package classes need no import, including compiled ones on the classpath
            for (Symbol member : unit.packge.members().getSymbols()) {
                if (member instanceof ClassSymbol c) {
                    indexMembers(c);
                }
            }
        }
        for (JCTree def : unit.defs) {
            if (!(def instanceof JCImport imp)) {
                continue;
            }
            JCFieldAccess q = imp.qualid;
            String owner = imp.staticImport || q.name == names.asterisk ? q.selected.toString() : q.toString();
            if (elements.getTypeElement(owner) instanceof ClassSymbol c) {
                indexMembers(c);
            }
        }
    }

    void indexMembers(ClassSymbol c) {
        for (Symbol m : c.members().getSymbols()) {
            if (m instanceof MethodSymbol method) {
                if (usingCount(method) > 0) {
                    usingMethodNames.add(method.name);
                }
                if (returnsKind(method)) {
                    kindMethodNames.add(method.name);
                }
                if (isExtension(method)) {
                    extensionNames.add(method.name);
                }
            }
        }
    }

    boolean returnsKind(MethodSymbol m) {
        return kindSymbol != null && m.type.getReturnType().tsym == kindSymbol;
    }

    // ------------------------------------------------------------------ one call

    /**
     * One call, with what every rule needs: its scope, its arguments' types (type checked on
     * copies), and the reporting mode. {@link Extensions} and {@link Lifting} work on it.
     */
    final class CallSite {
        final JCCompilationUnit unit;
        final JCMethodInvocation call;
        final Env<AttrContext> env;
        private final JavacScope scope;
        final TreePath path;
        final Lifting lifting;

        CallSite(JCCompilationUnit unit, TreePath path, JCMethodInvocation call) {
            this.unit = unit;
            this.call = call;
            this.path = path;
            // getScope type checks a copy of the enclosing statement, which in half-resolved code
            // has errors; they are not real, so neither reported nor cached
            Object saved = swapArgumentCache(new HashMap<>());
            Log.DiagnosticHandler discard = log.new DiscardDiagnosticHandler();
            try {
                this.scope = (JavacScope) trees.getScope(path);
            } finally {
                log.popDiagnosticHandler(discard);
                swapArgumentCache(saved);
            }
            this.env = scope.getEnv();
            this.lifting = new Lifting(ImplicitResolver.this, this);
        }

        /** Local {@code given} variables declared before this call in its enclosing blocks. */
        List<Symbol> localGivens() {
            Set<Name> declared = new java.util.HashSet<>();
            Tree child = path.getLeaf();
            for (TreePath p = path.getParentPath(); p != null; child = p.getLeaf(), p = p.getParentPath()) {
                if (p.getLeaf() instanceof BlockTree block) {
                    for (StatementTree statement : block.getStatements()) {
                        if (statement == child) {
                            break;
                        }
                        if (statement instanceof JCVariableDecl v && v.mods.annotations.stream()
                                .anyMatch(a -> a.annotationType.toString().equals("forj.Given"))) {
                            declared.add(v.name);
                        }
                    }
                }
            }
            List<Symbol> found = new ArrayList<>();
            for (Element e : scope.getLocalElements()) {
                if (e instanceof VarSymbol v && declared.contains(v.name)) {
                    found.add(v);
                }
            }
            return found;
        }

        Outcome complete(boolean report) {
            this.report = report;
            Name name = TreeInfo.name(call.meth);
            List<MethodSymbol> candidates = new ArrayList<>();
            boolean plainOverload = false;
            for (Symbol s : methodsNamed(name)) {
                MethodSymbol m = (MethodSymbol) s;
                int params = m.params().size();
                int using = usingCount(m);
                boolean varargs = usingBeforeVarargs(m);
                if (params == call.args.size() && !varargs) {
                    plainOverload |= accepts(m);
                } else if (varargs && usingsInPlace(m)) {
                    plainOverload = true;   // of(m, a, b): resolved already, or written that way
                } else if (using > 0 && (params - using == call.args.size()
                        || varargs && call.args.size() >= params - using - 1)) {
                    candidates.add(m);
                }
            }
            if (plainOverload || candidates.isEmpty()) {
                return Outcome.SKIPPED; // an ordinary call; javac handles it
            }
            if (candidates.size() > 1) {
                // overloads that differ in their ordinary parameters: decode(byte[]) vs decode(InputStream)
                candidates.removeIf(m -> !accepts(m, enabled(LIFT_ARGUMENTS)));
            }
            if (candidates.isEmpty()) {
                return Outcome.SKIPPED; // none takes these arguments; javac reports it
            }
            if (candidates.size() > 1) {
                return error(call, "ambiguous call: " + candidates.size() + " methods named " + name + " take using parameters");
            }
            MethodSymbol method = candidates.getFirst();
            if (!usingParamsTrail(method)) {
                return error(call, "using parameters of " + method + " must come last");
            }
            if (usingBeforeVarargs(method) && passesUsings(method)) {
                // of(a, b)(using m), rewritten to of(a, b, m): m goes before the varargs
                int k = firstUsing(method);
                int u = usingCount(method);
                int n = call.args.size();
                ListBuffer<JCExpression> moved = new ListBuffer<>();
                moved.addAll(call.args.subList(0, k));
                moved.addAll(call.args.subList(n - u, n));
                moved.addAll(call.args.subList(k, n - u));
                call.args = moved.toList();
                return Outcome.RESOLVED;
            }

            if (enabled(LIFT_ARGUMENTS) && lifting.liftArguments(method)) {
                argTypes = null;   // the lifted arguments are Kinds now: infer from those
            }
            Map<TypeVar, Type> bindings = inferTypeArguments(method);
            // Object from an argument usually means the argument's own type variable was left
            // unconstrained (Routes.of(constant("a")) speculates constant("a") alone, as a
            // Route<Object>), never what the user meant: the target, when there is one, wins
            List<Type> tvars = method.type.getTypeArguments();
            if (call.typeargs.isEmpty() && tvars.stream().anyMatch(tv -> {
                Type bound = bindings.get((TypeVar) tv);
                return bound == null || types.isSameType(bound, objectType);
            })) {
                Type target = targetType();
                if (target != null && !target.isErroneous()) {
                    // a JDK target (Callable<User>) stands for the Kind its @Lower method takes, but only
                    // for methods returning a Kind: `<A> A decode(...)` into a List<String> means A = List<String>
                    Lifting.Lowering lowering = enabled(LIFT_RESULTS) && returnsKind(method) ? lifting.lowering(target) : null;
                    Map<TypeVar, Type> fromTarget = new HashMap<>();
                    unify(method.type.getReturnType(), lowering != null ? lowering.kind() : target, fromTarget, tvars);
                    fromTarget.forEach((tv, t) -> {
                        Type bound = bindings.get(tv);
                        if (bound == null || types.isSameType(bound, objectType)) {
                            bindings.put(tv, t);
                        }
                    });
                }
            }
            trace("  args " + argTypes() + " bindings " + bindings);
            ListBuffer<JCExpression> given = new ListBuffer<>();
            List<VarSymbol> params = method.params();
            List<Type> paramTypes = method.type.getParameterTypes();
            for (int i = 0; i < params.size(); i++) {
                if (!isUsing(params.get(i))) {
                    continue;
                }
                Type required = substitute(paramTypes.get(i), bindings);
                if (hasUnbound(required, method, bindings)) {
                    return error(call, "cannot infer the type of using parameter " + params.get(i).name + " (" + required
                            + ") of " + method.name + "; pass type arguments explicitly, e.g. Owner.<T>" + method.name + "(...)");
                }
                Search search = new Search();
                JCExpression found = search.find(required, 0);
                if (found == null) {
                    return error(call, search.failure(required, method));
                }
                given.append(found);
            }
            // the using arguments go where the using parameters are: last, or before a varargs one
            int k = firstUsing(method);
            ListBuffer<JCExpression> args = new ListBuffer<>();
            args.addAll(call.args.subList(0, k));
            args.addAll(given.toList());
            args.addAll(call.args.subList(k, call.args.size()));
            call.args = args.toList();
            if (method.owner == forClass || isExtension(method)) {
                typeLambdaParameters(method, bindings);
            }
            return Outcome.RESOLVED;
        }

        /**
         * An expression for the class {@code c}, to call its static members on. Normally its full
         * name ({@code lib.Ords}). When a variable in scope has the name of the first package
         * ({@code int lib = 1;}), that name would mean the variable, so the class is reached
         * through a cast instead, {@code ((lib.Ords) null)}: a cast's type is resolved as a type,
         * which a variable never hides, and a static member can be called through it.
         */
        JCExpression owner(ClassSymbol c) {
            JCExpression name = qualified(c);
            if (!(name instanceof JCFieldAccess)) {
                return name;   // a top-level class in the unnamed package: no package to shadow
            }
            Name root = TreeInfo.name(rootOf(name));
            if (!variableNames(unit).contains(root) || !shadowed(root)) {
                return name;
            }
            return castedNull(name);
        }

        private JCExpression castedNull(JCExpression type) {
            return make.Parens(make.TypeCast(type, make.Literal(com.sun.tools.javac.code.TypeTag.BOT, null)));
        }

        static boolean isCastedNull(JCExpression e) {
            return e instanceof JCTree.JCParens p && p.expr instanceof JCTree.JCTypeCast cast
                    && cast.expr instanceof JCTree.JCLiteral literal && literal.typetag == com.sun.tools.javac.code.TypeTag.BOT;
        }

        private static JCExpression rootOf(JCExpression e) {
            while (e instanceof JCFieldAccess f) {
                e = f.selected;
            }
            return e;
        }

        /** Does {@code name} mean a variable here (a local, a parameter or a field)? */
        private boolean shadowed(Name name) {
            JCTree.JCIdent ident = make.Ident(name);
            Env<AttrContext> scratch = env.dup(ident);
            Object saved = swapArgumentCache(new HashMap<>());
            Log.DiagnosticHandler discard = log.new DiscardDiagnosticHandler();
            try {
                attr.attribExpr(ident, scratch);
            } catch (RuntimeException e) {
                return false;
            } finally {
                log.popDiagnosticHandler(discard);
                swapArgumentCache(saved);
            }
            return ident.sym != null && ident.sym.kind == Kind.VAR;
        }

        /** The desugarer's {@code forj.For.m(...)} where {@code forj} means a variable here: call it through a cast. */
        void unshadow(JCFieldAccess outer, JCFieldAccess forjFor) {
            if (shadowed(((JCTree.JCIdent) forjFor.selected).name)) {
                make.at(outer.pos);
                outer.selected = castedNull(forjFor);
            }
        }

        boolean enabled(String feature) {
            return features(unit).contains(feature);
        }

        /**
         * Gives the implicit lambda parameters of a resolved comprehension step their types
         * ({@code x -> ...} becomes {@code (String x) -> ...}). javac can then type the lambda
         * body on its own, which nested steps need: until the enclosing generic call is fully
         * inferred, javac only has an inference variable for {@code x}.
         */
        private void typeLambdaParameters(MethodSymbol method, Map<TypeVar, Type> bindings) {
            List<Type> paramTypes = method.type.getParameterTypes();
            boolean varargs = (method.flags() & Flags.VARARGS) != 0;
            for (int i = 0; i < call.args.size() && (varargs || i < paramTypes.size()); i++) {
                if (!(call.args.get(i) instanceof JCLambda lambda)
                        || lambda.paramKind != JCLambda.ParameterKind.IMPLICIT) {
                    continue;
                }
                Type param = varargs && i >= paramTypes.size() - 1
                        ? ((ArrayType) paramTypes.getLast()).elemtype : paramTypes.get(i);
                List<Type> inputs = descriptorInputs(substitute(param, bindings));
                if (inputs == null || inputs.size() != lambda.params.size()
                        || inputs.stream().anyMatch(t -> hasUnbound(t, method, bindings) || !isPlain(t))) {
                    continue;
                }
                for (int k = 0; k < inputs.size(); k++) {
                    JCVariableDecl p = lambda.params.get(k);
                    make.at(p.pos);
                    p.vartype = typeTree(inputs.get(k));
                    p.declKind = JCVariableDecl.DeclKind.EXPLICIT;
                }
                lambda.paramKind = JCLambda.ParameterKind.EXPLICIT;
            }
        }

        /**
         * The type the call's value goes into, if its context says: a typed local variable, a
         * return from the enclosing method, or an assignment. Used to infer type parameters
         * that only the result mentions ({@code IO<Unit> app = checkout(cart);} gives F = IO).
         */
        Type targetType() {
            TreePath parent = path.getParentPath();
            if (parent.getLeaf() instanceof JCMethodInvocation wrapper
                    && TreeInfo.name(wrapper.meth).contentEquals("forjNarrow")) {
                parent = parent.getParentPath();
            }
            return switch (parent.getLeaf()) {
                case JCVariableDecl v when v.vartype != null && !v.declaredUsingVar() -> speculate(v.vartype, true);
                case JCReturn r -> insideLambda(parent) || env.enclMethod == null || env.enclMethod.sym == null
                        ? null : env.enclMethod.sym.type.getReturnType();
                case JCAssign a -> speculate(a.lhs, false);
                case JCMethodInvocation outer when outer.args.stream().anyMatch(arg -> arg == path.getLeaf()) ->
                        argumentTarget(outer, (JCExpression) path.getLeaf());
                default -> null;
            };
        }

        /**
         * This call is an argument of {@code outer}: the type of the parameter it is passed to,
         * when every method {@code outer} could be agrees on it. Type variables of that method's
         * class come from its receiver ({@code app.through(errors())} on an {@code HttpApp<IO>}
         * wants a {@code Middleware<IO>}). A parameter that mentions the outer method's own type
         * variables gives nothing: those need the outer call inferred first
         * ({@code describe(checkout(order))}).
         */
        private Type argumentTarget(JCMethodInvocation outer, JCExpression arg) {
            int i = outer.args.indexOf(arg);
            Type found = null;
            for (Symbol s : methodsNamed(outer.meth, TreeInfo.name(outer.meth))) {
                MethodSymbol m = (MethodSymbol) s;
                Type param = parameterAt(m, i, outer.args.size());
                if (param == null) {
                    continue;
                }
                Map<TypeVar, Type> bindings = new HashMap<>();
                if (!bindReceiver(outer.meth, m, bindings)) {
                    return null;
                }
                Type t = substitute(param, bindings);
                if (hasUnbound(t, m, bindings) || found != null && !types.isSameType(found, t)) {
                    return null;
                }
                found = t;
            }
            return found;
        }

        /**
         * The declared type of parameter {@code i} when {@code m} is called with {@code n}
         * arguments: all of them passed (an ordinary call, or one resolved already), or only the
         * ordinary ones (using arguments still to come). Null when {@code m} takes no {@code n}.
         */
        private Type parameterAt(MethodSymbol m, int i, int n) {
            boolean varargs = (m.flags() & Flags.VARARGS) != 0;
            List<Type> all = m.type.getParameterTypes();
            List<Type> ordinary = new ArrayList<>();
            for (int j = 0; j < all.size(); j++) {
                if (!isUsing(m.params().get(j))) {
                    ordinary.add(all.get(j));
                }
            }
            for (List<Type> shape : List.of(all, ordinary)) {
                if (varargs ? n >= shape.size() - 1 : n == shape.size()) {
                    if (varargs && i >= shape.size() - 1) {
                        return ((ArrayType) shape.getLast()).elemtype;
                    }
                    return i < shape.size() ? shape.get(i) : null;
                }
            }
            return null;
        }

        private boolean insideLambda(TreePath from) {
            for (TreePath p = from; p != null; p = p.getParentPath()) {
                if (p.getLeaf() instanceof JCLambda) {
                    return true;
                }
                if (p.getLeaf() instanceof JCMethodDecl) {
                    return false;
                }
            }
            return false;
        }

        /**
         * Writes out the type arguments of a resolved comprehension step,
         * {@code forj.For.<F, A, B>forjMap(...)}. Without them javac infers them, and nested
         * steps whose lambdas capture outer bindings are more than its inference recovers from.
         * F and A come from the first argument, B from the type of the expression the lambda
         * returns (for flatMap, the B of the {@code Kind<F, B>} it returns).
         */
        Outcome pinTypeArguments() {
            Type fa = argTypes().getFirst();
            Type kind = fa == null ? null : types.asSuper(fa, kindSymbol);
            if (kind == null || kind.getTypeArguments().size() != 2) {
                return Outcome.PENDING;
            }
            Type f = kind.getTypeArguments().get(0);
            Type a = kind.getTypeArguments().get(1);
            Name name = TreeInfo.name(call.meth);
            ListBuffer<Type> typeArgs = new ListBuffer<Type>().append(f).append(a);
            if (!name.contentEquals("forjFilter")) {
                Type returned = returnedType((JCLambda) call.args.get(1));
                if (returned == null) {
                    return Outcome.PENDING;
                }
                Type b;
                if (name.contentEquals("forjFlatMap")) {
                    Type inner = types.asSuper(returned, kindSymbol);
                    if (inner == null || inner.getTypeArguments().size() != 2) {
                        return Outcome.PENDING;
                    }
                    b = inner.getTypeArguments().get(1);
                } else {
                    b = returned.isPrimitive() ? types.boxedClass(returned).type : returned;
                }
                typeArgs.append(b);
            }
            if (typeArgs.stream().anyMatch(t -> !isPlain(t))) {
                return Outcome.SKIPPED; // leave it to javac's inference
            }
            ListBuffer<JCExpression> trees = new ListBuffer<>();
            for (Type t : typeArgs) {
                trees.append(typeTree(t));
            }
            call.typeargs = trees.toList();
            return Outcome.RESOLVED;
        }

        /** The type of what a comprehension step's lambda returns, type checked in its own scope. */
        private Type returnedType(JCLambda lambda) {
            JCExpression result = lambda.body instanceof JCBlock block && block.stats.last() instanceof JCReturn ret
                    ? ret.expr
                    : lambda.body instanceof JCExpression e ? e : null;
            if (result == null || lambda.paramKind != JCLambda.ParameterKind.EXPLICIT) {
                return null;
            }
            TreePath at = trees.getPath(unit, result);
            if (at == null) {
                return null;
            }
            Type t = new CallSite(unit, at, null).speculate(result, false);
            return t == null || t.isErroneous() ? null : t;
        }

        List<Type> argTypes;

        /** Argument types, type checked once on copies; null where unknown (e.g. lambdas). */
        List<Type> argTypes() {
            if (argTypes == null) {
                argTypes = new ArrayList<>();
                for (JCExpression arg : call.args) {
                    Type t = speculate(arg, false);
                    argTypes.add(t == null || t.isErroneous() ? null : t);
                }
            }
            return argTypes;
        }

        /** Could {@code m}'s ordinary parameters take the call's arguments? Unknown counts as yes. */
        private boolean accepts(MethodSymbol m) {
            return accepts(m, false);
        }

        /** As {@link #accepts(MethodSymbol)}; with {@code lifting}, a liftable JDK value also fits a Kind parameter. */
        private boolean accepts(MethodSymbol m, boolean lifting) {
            for (int i = 0; i < call.args.size(); i++) {
                Type arg = argTypes().get(i);
                Type declared = paramFor(m, i);
                if (declared == null) {
                    break;
                }
                Type param = types.erasure(declared);
                if (arg != null && !types.isConvertible(arg, param)
                        && !(lifting && this.lifting.isKindType(param) && this.lifting.lift(call.args.get(i), arg) != null)) {
                    return false;
                }
            }
            return true;
        }

        // ------------------------------------------------- which methods could this be?

        Set<Symbol> methodsNamed(Name name) {
            return methodsNamed(call.meth, name);
        }

        /** The methods {@code meth(...)}, called from this call's scope, could mean. */
        private Set<Symbol> methodsNamed(JCExpression meth, Name name) {
            Set<Symbol> found = new LinkedHashSet<>();
            if (meth instanceof JCFieldAccess select) {
                Type asValue = speculate(select.selected, false);
                boolean viaType = asValue == null || asValue.isErroneous();
                Type site = viaType ? speculate(select.selected, true) : asValue;
                if (site != null && !site.isErroneous()) {
                    addMembers(site, name, found);
                }
                if (viaType) {
                    // `Type.m(...)` can only mean a static method
                    found.removeIf(s -> (s.flags() & Flags.STATIC) == 0);
                }
                return found;
            }
            for (Env<AttrContext> e = env; e != null; e = e.outer) {
                if (e.enclClass != null && e.enclClass.sym != null) {
                    addMembers(e.enclClass.sym.type, name, found);
                }
            }
            for (Symbol s : unit.namedImportScope.getSymbolsByName(name)) {
                if (s.kind == Kind.MTH) {
                    found.add(s);
                }
            }
            for (Symbol s : unit.starImportScope.getSymbolsByName(name)) {
                if (s.kind == Kind.MTH) {
                    found.add(s);
                }
            }
            return found;
        }

        void addMembers(Type site, Name name, Set<Symbol> into) {
            for (Type t : types.closure(site)) {
                for (Symbol s : t.tsym.members().getSymbolsByName(name)) {
                    if (s.kind == Kind.MTH) {
                        into.add(s);
                    }
                }
            }
        }

        // ------------------------------------------------------------- inference

        /** Binds the method's type variables from explicit type arguments and argument types. */
        private Map<TypeVar, Type> inferTypeArguments(MethodSymbol method) {
            Map<TypeVar, Type> bindings = new HashMap<>();
            bindReceiver(call.meth, method, bindings);
            List<Type> tvars = method.type.getTypeArguments();
            if (call.typeargs.nonEmpty()) {
                if (call.typeargs.size() != tvars.size()) {
                    return bindings; // javac reports the mismatch
                }
                for (int i = 0; i < tvars.size(); i++) {
                    Type t = speculate(call.typeargs.get(i), true);
                    if (t != null && !t.isErroneous()) {
                        bindings.put((TypeVar) tvars.get(i), t);
                    }
                }
                return bindings;
            }
            for (int i = 0; i < call.args.size(); i++) {
                Type arg = argTypes().get(i);
                Type param = paramFor(method, i);
                if (arg != null && param != null) {
                    unify(param, arg, bindings, tvars);
                }
            }
            return bindings;
        }

        /**
         * {@code recv.m(...)} on an instance method of a generic class: binds the class's type
         * variables from the receiver's type, so {@code routes.orNotFound()} on a
         * {@code Routes<IO>} needs an {@code Applicative<IO>}, not an {@code Applicative<F>}.
         */
        /**
         * Whether the receiver's type is known, when there is one: false for a receiver that
         * doesn't type check (yet), or a raw one.
         */
        private boolean bindReceiver(JCExpression meth, MethodSymbol method, Map<TypeVar, Type> bindings) {
            if ((method.flags() & Flags.STATIC) != 0 || !(meth instanceof JCFieldAccess select)
                    || isCastedNull(select.selected)) {
                return true;
            }
            Type receiver = speculate(select.selected, false);
            if (receiver == null || receiver.isErroneous()) {
                return false;
            }
            Type site = types.asSuper(types.skipTypeVars(receiver, false), method.owner);
            if (site == null) {
                return false;
            }
            List<Type> params = method.owner.type.getTypeArguments();
            List<Type> args = site.getTypeArguments();
            if (args.size() < params.size()) {
                return false;   // raw
            }
            for (int i = 0; i < params.size(); i++) {
                if (params.get(i) instanceof TypeVar v && !(args.get(i) instanceof WildcardType)) {
                    bindings.put(v, args.get(i));
                }
            }
            return true;
        }

        /**
         * The declared type of the parameter that the call's {@code i}-th ordinary argument goes
         * to, skipping using parameters; for the varargs part, the element type (or the array,
         * when one is passed as is). Null when there is none.
         */
        Type paramFor(MethodSymbol m, int i) {
            List<Type> ordinary = new ArrayList<>();
            List<VarSymbol> params = m.params();
            List<Type> declared = m.type.getParameterTypes();
            for (int j = 0; j < params.size(); j++) {
                if (!isUsing(params.get(j))) {
                    ordinary.add(declared.get(j));
                }
            }
            if (usingBeforeVarargs(m) && i >= ordinary.size() - 1) {
                Type array = ordinary.getLast();
                Type arg = argTypes().get(i);
                boolean asArray = call.args.size() == ordinary.size() && arg != null
                        && types.isConvertible(arg, types.erasure(array));
                return asArray ? array : ((ArrayType) array).elemtype;
            }
            return i < ordinary.size() ? ordinary.get(i) : null;
        }

        /** Whether the arguments at the using parameters' positions are instances of their types. */
        private boolean usingsInPlace(MethodSymbol m) {
            int k = firstUsing(m);
            int u = usingCount(m);
            if (call.args.size() < k + u) {
                return false;
            }
            List<Type> paramTypes = m.type.getParameterTypes();
            for (int j = k; j < k + u; j++) {
                Type arg = argTypes().get(j);
                if (arg == null || !types.isConvertible(arg, types.erasure(paramTypes.get(j)))) {
                    return false;
                }
            }
            return true;
        }

        /** Whether the call passes the using arguments itself, after the varargs ones (see SourceRewriter). */
        private boolean passesUsings(MethodSymbol m) {
            int k = firstUsing(m);
            int u = usingCount(m);
            int n = call.args.size();
            if (n < k + u) {
                return false;
            }
            List<Type> paramTypes = m.type.getParameterTypes();
            for (int j = 0; j < u; j++) {
                Type arg = argTypes().get(n - u + j);
                if (arg == null || !types.isConvertible(arg, types.erasure(paramTypes.get(k + j)))) {
                    return false;
                }
            }
            Type element = ((ArrayType) paramTypes.getLast()).elemtype;
            Type last = argTypes().get(n - 1);
            return !types.isConvertible(last, types.erasure(element));
        }

        /** Type checks a copy of {@code tree} in this call's scope, reporting nothing. */
        Type speculate(JCTree tree, boolean asType) {
            JCTree copy = new TreeCopier<Void>(make).copy(tree);
            Env<AttrContext> scratch = env.dup(copy); // env is getScope's private copy already
            var discard = TRACE ? log.new DeferredDiagnosticHandler() : log.new DiscardDiagnosticHandler();
            Object saved = swapArgumentCache(new HashMap<>());
            try {
                Type t = asType ? attr.attribType(copy, scratch) : attr.attribExpr(copy, scratch);
                if (TRACE && (t == null || t.isErroneous()) && discard instanceof Log.DeferredDiagnosticHandler d) {
                    trace("    speculate " + tree + " -> " + t + " " + d.getDiagnostics().stream().map(x -> x.getMessage(null)).toList()
                            + " locals " + scope.getLocalElements());
                }
                return t;
            } catch (RuntimeException e) {
                trace("    speculate " + tree + " threw " + e);
                return null;
            } finally {
                swapArgumentCache(saved);
                log.popDiagnosticHandler(discard);
            }
        }

        boolean report;

        /** Reports in the final pass; earlier passes just wait for more types to be known. */
        Outcome error(JCTree at, String message) {
            if (!report) {
                return Outcome.PENDING;
            }
            trees.printMessage(Diagnostic.Kind.ERROR, "forj: " + message, at, unit);
            return Outcome.SKIPPED;
        }

        // ------------------------------------------------------------ the search

        private final class Search {
            private final List<String> rejected = new ArrayList<>();
            private final List<String> ambiguous = new ArrayList<>();

            /** An expression for a given of type {@code required}, or null. */
            JCExpression find(Type required, int depth) {
                if (depth > MAX_DEPTH) {
                    rejected.add("gave up after " + MAX_DEPTH + " nested givens (divergent?)");
                    return null;
                }
                for (List<Symbol> level : levels(required)) {
                    List<JCExpression> matches = new ArrayList<>();
                    List<Symbol> matched = new ArrayList<>();
                    for (Symbol candidate : level) {
                        JCExpression e = tryCandidate(candidate, required, depth);
                        if (e != null) {
                            matches.add(e);
                            matched.add(candidate);
                        }
                    }
                    if (matches.size() == 1) {
                        return matches.getFirst();
                    }
                    if (matches.size() > 1) {
                        ambiguous.add(required + ": " + matched.stream().map(ImplicitResolver::describe).toList());
                        return null;
                    }
                }
                return null;
            }

            String failure(Type required, MethodSymbol method) {
                if (!ambiguous.isEmpty()) {
                    return "ambiguous givens for " + String.join("; ", ambiguous);
                }
                String why = rejected.isEmpty() ? "" : " (" + String.join("; ", rejected) + ")";
                return "no given " + required + " for " + method.name + why;
            }

            private JCExpression tryCandidate(Symbol candidate, Type required, int depth) {
                make.at(call.pos);
                if (candidate instanceof VarSymbol v) {
                    if (!types.isSubtype(v.type, required)) {
                        return null;
                    }
                    if (v.owner.kind == Kind.MTH) {
                        return make.Ident(v.name); // a using parameter of the enclosing method
                    }
                    return make.Select(owner((ClassSymbol) v.owner), v.name);
                }
                MethodSymbol m = (MethodSymbol) candidate;
                List<Type> tvars = m.type.getTypeArguments();
                Map<TypeVar, Type> bindings = new HashMap<>();
                unify(m.type.getReturnType(), required, bindings, tvars);
                Type result = substitute(m.type.getReturnType(), bindings);
                if (hasUnbound(result, m, bindings) || !types.isSubtype(result, required)) {
                    return null;
                }
                ListBuffer<JCExpression> args = new ListBuffer<>();
                List<Type> paramTypes = m.type.getParameterTypes();
                for (int i = 0; i < paramTypes.size(); i++) {
                    Type needed = substitute(paramTypes.get(i), bindings);
                    JCExpression arg = find(needed, depth + 1);
                    if (arg == null) {
                        rejected.add(describe(m) + " needs " + needed);
                        return null;
                    }
                    args.append(arg);
                }
                // Explicit type arguments: then javac doesn't treat the call as a poly expression,
                // which it would cache by source position (shared with the call we insert into).
                ListBuffer<JCExpression> typeArgs = new ListBuffer<>();
                for (Type tv : tvars) {
                    Type bound = bindings.get((TypeVar) tv);
                    if (bound == null) {
                        return null;
                    }
                    typeArgs.append(typeTree(bound));
                }
                make.at(call.pos);
                return make.Apply(typeArgs.toList(),
                        make.Select(owner((ClassSymbol) m.owner), m.name), args.toList());
            }

            /** Candidate givens, nearest scope first. */
            private List<List<Symbol>> levels(Type required) {
                List<List<Symbol>> levels = new ArrayList<>();

                levels.add(localGivens());

                List<Symbol> params = new ArrayList<>();
                if (env.enclMethod != null && env.enclMethod.sym != null) {
                    for (VarSymbol p : env.enclMethod.sym.params()) {
                        if (isUsing(p)) {
                            params.add(p);
                        }
                    }
                }
                levels.add(params);

                Set<Symbol> enclosing = new LinkedHashSet<>();
                for (Env<AttrContext> e = env; e != null; e = e.outer) {
                    if (e.enclClass != null && e.enclClass.sym != null) {
                        for (Type t : types.closure(e.enclClass.sym.type)) {
                            addGivens(t.tsym, enclosing);
                        }
                    }
                }
                levels.add(new ArrayList<>(enclosing));

                Set<Symbol> imported = new LinkedHashSet<>();
                for (Symbol s : unit.namedImportScope.getSymbols()) {
                    if (isGiven(s)) {
                        imported.add(s);
                    }
                }
                for (Symbol s : unit.starImportScope.getSymbols()) {
                    if ((s.kind == Kind.VAR || s.kind == Kind.MTH) && isGiven(s)) {
                        imported.add(s);
                    }
                }
                levels.add(new ArrayList<>(imported));

                Set<Symbol> companions = new LinkedHashSet<>();
                addCompanions(required, companions, new java.util.HashSet<>());
                levels.add(new ArrayList<>(companions));

                // instances for JDK types, which can't live next to List or Optional
                Set<Symbol> defaults = new LinkedHashSet<>();
                addGivens(defaultInstances, defaults);
                levels.add(new ArrayList<>(defaults));

                levels.replaceAll(level -> level.stream()
                        .filter(s -> s.owner.kind == Kind.MTH || rs.isAccessible(env, s.owner.type, s))
                        .toList());
                return levels;
            }

            private void addCompanions(Type t, Set<Symbol> into, Set<Symbol> seen) {
                if (t == null || t.tsym == null || !seen.add(t.tsym)) {
                    return;
                }
                for (Type s : types.closure(t)) {
                    addGivens(s.tsym, into);
                }
                for (Type arg : t.getTypeArguments()) {
                    addCompanions(arg instanceof WildcardType w ? w.type : arg, into, seen);
                }
            }

            private void addGivens(Symbol owner, Set<Symbol> into) {
                if (!(owner instanceof ClassSymbol c)) {
                    return;
                }
                for (Symbol s : c.members().getSymbols()) {
                    if (isGiven(s) && (s.flags() & Flags.STATIC) != 0) {
                        into.add(s);
                    }
                }
            }
        }
    }

    /**
     * javac caches the types of poly arguments by source position (ArgumentAttr). What the
     * resolver attributes in half-resolved code must not end up in the cache the real
     * attribution uses, so each such attribution runs against its own cache.
     */
    Object swapArgumentCache(Object cache) {
        try {
            Object previous = argumentCache.get(argumentAttr);
            argumentCache.set(argumentAttr, cache);
            return previous;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A call whose value is assigned or returned (and not already narrowed). */
    private static boolean narrowable(TreePath path) {
        Tree call = path.getLeaf();
        return switch (path.getParentPath().getLeaf()) {
            case JCVariableDecl v -> v.init == call && v.vartype != null && !v.declaredUsingVar();
            case JCReturn r -> r.expr == call;
            case JCAssign a -> a.rhs == call;
            default -> false;
        };
    }

    /** A comprehension step the resolver has passed its instance to, but whose type arguments are still inferred. */
    private boolean isUnpinnedStep(JCMethodInvocation call, Name name) {
        return call.typeargs.isEmpty() && call.args.size() == 3
                && call.meth instanceof JCFieldAccess select && select.selected.toString().equals("forj.For")
                && (name.contentEquals("forjFlatMap") || name.contentEquals("forjMap") || name.contentEquals("forjFilter"));
    }

    // ------------------------------------------------------------------- types

    /** Parameter types of a functional interface type's method, wildcards replaced by their bounds. */
    List<Type> descriptorInputs(Type functional) {
        if (!(functional.tsym instanceof ClassSymbol c) || !c.isInterface()) {
            return null;
        }
        ListBuffer<Type> args = new ListBuffer<>();
        for (Type a : functional.getTypeArguments()) {
            args.append(a instanceof WildcardType w ? (w.type != null ? w.type : types.erasure(c.type)) : a);
        }
        Type plain = new Type.ClassType(functional.getEnclosingType(), args.toList(), c);
        try {
            Symbol descriptor = types.findDescriptorSymbol(c);
            return types.memberType(plain, descriptor).getParameterTypes();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A type that can be written in source as is: classes, arrays and type variables, no captures. */
    private boolean isPlain(Type t) {
        if (t instanceof Type.CapturedType || t.isErroneous() || t.isPrimitiveOrVoid() || t.hasTag(com.sun.tools.javac.code.TypeTag.BOT)) {
            return false;
        }
        if (t instanceof ArrayType a) {
            return isPlain(a.elemtype);
        }
        if (t instanceof TypeVar || t instanceof Type.ClassType) {
            for (Type arg : t.getTypeArguments()) {
                Type inner = arg instanceof WildcardType w ? w.type : arg;
                if (inner != null && !isPlain(inner)) {
                    return false;
                }
            }
            return !(t instanceof Type.IntersectionClassType);
        }
        return false;
    }

    /** Binds type variables of {@code pattern} so it matches {@code actual} (one-sided, best effort). */
    void unify(Type pattern, Type actual, Map<TypeVar, Type> bindings, List<Type> tvars) {
        if (pattern instanceof TypeVar v && tvars.contains(v)) {
            bindings.putIfAbsent(v, actual.isPrimitive() ? types.boxedClass(actual).type : actual);
            return;
        }
        if (pattern instanceof WildcardType w) {
            if (w.type != null) {
                unify(w.type, actual instanceof WildcardType aw && aw.type != null ? aw.type : actual, bindings, tvars);
            }
            return;
        }
        if (pattern instanceof ArrayType pa && actual instanceof ArrayType aa) {
            unify(pa.elemtype, aa.elemtype, bindings, tvars);
            return;
        }
        if (pattern.getTypeArguments().nonEmpty() && pattern.tsym != null) {
            Type sup = types.asSuper(actual, pattern.tsym);
            if (sup == null) {
                // a given returning a subtype of what's needed (Monoid<List<A>> for a Semigroup<List<Integer>>)
                Type viewed = actual.tsym == null ? null : types.asSuper(pattern, actual.tsym);
                if (viewed != null && viewed.tsym != pattern.tsym) {
                    unify(viewed, actual, bindings, tvars);
                }
                return;
            }
            var ps = pattern.getTypeArguments();
            var as = sup.getTypeArguments();
            for (int i = 0; i < Math.min(ps.size(), as.size()); i++) {
                Type a = as.get(i);
                unify(ps.get(i), a instanceof WildcardType aw && aw.type != null ? aw.type : a, bindings, tvars);
            }
        }
    }

    Type substitute(Type t, Map<TypeVar, Type> bindings) {
        ListBuffer<Type> from = new ListBuffer<>();
        ListBuffer<Type> to = new ListBuffer<>();
        bindings.forEach((k, v) -> {
            from.append(k);
            to.append(v);
        });
        return types.subst(t, from.toList(), to.toList());
    }

    /** Mentions one of {@code method}'s own type variables that {@code bindings} doesn't bind. */
    private boolean hasUnbound(Type t, MethodSymbol method, Map<TypeVar, Type> bindings) {
        List<Type> tvars = method.type.getTypeArguments().stream().filter(v -> !bindings.containsKey((TypeVar) v)).toList();
        if (tvars.isEmpty()) {
            return false;
        }
        boolean[] found = {false};
        new Types.UnaryVisitor<Void>() {
            @Override
            public Void visitType(Type type, Void s) {
                if (tvars.contains(type)) {
                    found[0] = true;
                }
                for (Type arg : type.getTypeArguments()) {
                    visit(arg);
                }
                if (type instanceof WildcardType w && w.type != null) {
                    visit(w.type);
                }
                if (type instanceof ArrayType a) {
                    visit(a.elemtype);
                }
                return null;
            }
        }.visit(t);
        return found[0];
    }

    // ----------------------------------------------------------------- symbols

    private boolean isGiven(Symbol s) {
        return (s.kind == Kind.VAR || s.kind == Kind.MTH) && s.attribute(givenAnnotation) != null;
    }

    boolean isUsing(VarSymbol p) {
        return p.attribute(usingAnnotation) != null;
    }

    boolean isExtension(MethodSymbol m) {
        return extensionAnnotation != null && m.attribute(extensionAnnotation) != null;
    }

    int usingCount(MethodSymbol m) {
        int n = 0;
        for (VarSymbol p : m.params()) {
            if (isUsing(p)) {
                n++;
            }
        }
        return n;
    }

    /** Using parameters come last, or just before a varargs parameter. */
    private boolean usingParamsTrail(MethodSymbol m) {
        boolean seenUsing = false;
        List<VarSymbol> params = m.params();
        for (int i = 0; i < params.size(); i++) {
            if (isUsing(params.get(i))) {
                seenUsing = true;
            } else if (seenUsing && !(i == params.size() - 1 && (m.flags() & Flags.VARARGS) != 0)) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@code of(@Using Monad<F> m, Route<F>... rs)}: what {@code of(Route<F>... rs)(using Monad<F> m)}
     * becomes, since javac wants the varargs parameter last.
     */
    private boolean usingBeforeVarargs(MethodSymbol m) {
        List<VarSymbol> params = m.params();
        return (m.flags() & Flags.VARARGS) != 0 && !params.isEmpty() && !isUsing(params.getLast()) && usingCount(m) > 0;
    }

    /** Index of the first using parameter (the parameter count when there is none). */
    private int firstUsing(MethodSymbol m) {
        List<VarSymbol> params = m.params();
        for (int i = 0; i < params.size(); i++) {
            if (isUsing(params.get(i))) {
                return i;
            }
        }
        return params.size();
    }

    /**
     * A type written out as source would write it, with no symbols attached, so javac
     * attributes it like hand-written code ({@code TreeMaker.Type} pre-attributes its trees).
     */
    JCExpression typeTree(Type t) {
        if (t instanceof ArrayType a) {
            return make.TypeArray(typeTree(a.elemtype));
        }
        if (t instanceof TypeVar v) {
            return make.Ident(v.tsym.name);
        }
        if (t instanceof WildcardType w) {
            var kind = make.TypeBoundKind(w.kind);
            return make.Wildcard(kind, w.type == null || w.kind == com.sun.tools.javac.code.BoundKind.UNBOUND ? null : typeTree(w.type));
        }
        JCExpression base = qualified((ClassSymbol) t.tsym);
        if (t.getTypeArguments().isEmpty()) {
            return base;
        }
        ListBuffer<JCExpression> args = new ListBuffer<>();
        for (Type arg : t.getTypeArguments()) {
            args.append(typeTree(arg));
        }
        return make.TypeApply(base, args.toList());
    }

    private JCExpression qualified(ClassSymbol owner) {
        String[] parts = owner.getQualifiedName().toString().split("\\.");
        JCExpression e = make.Ident(names.fromString(parts[0]));
        for (int i = 1; i < parts.length; i++) {
            e = make.Select(e, names.fromString(parts[i]));
        }
        return e;
    }

    static String describe(Symbol s) {
        return s.owner.getSimpleName() + "." + s.getSimpleName();
    }
}
