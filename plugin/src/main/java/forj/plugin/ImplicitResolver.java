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
import com.sun.tools.javac.comp.Attr;
import com.sun.tools.javac.comp.AttrContext;
import com.sun.tools.javac.comp.Env;
import com.sun.tools.javac.comp.Resolve;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCFieldAccess;
import com.sun.tools.javac.tree.JCTree.JCImport;
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
 *       (Scala's implicit scope, the closest Java has to companion objects).</li>
 * </ol>
 * The inserted arguments are ordinary Java, so javac type checks them like any other code.
 */
final class ImplicitResolver {

    private static final int MAX_DEPTH = 16;

    private final Trees trees;
    private final Attr attr;
    private final Types types;
    private final Resolve rs;
    private final Log log;
    private final TreeMaker make;
    private final Names names;
    private final ClassSymbol givenAnnotation;
    private final ClassSymbol usingAnnotation;
    private final javax.lang.model.util.Elements elements;

    /** Names of methods known to take {@code using} parameters; calls to anything else are skipped quickly. */
    private final Set<Name> usingMethodNames = new java.util.HashSet<>();
    private final Set<Name> indexedPackages = new java.util.HashSet<>();

    ImplicitResolver(Context context, Trees trees, javax.lang.model.util.Elements elements,
                     ClassSymbol givenAnnotation, ClassSymbol usingAnnotation) {
        this.elements = elements;
        this.trees = trees;
        this.attr = Attr.instance(context);
        this.types = Types.instance(context);
        this.rs = Resolve.instance(context);
        this.log = Log.instance(context);
        this.make = TreeMaker.instance(context);
        this.names = Names.instance(context);
        this.givenAnnotation = givenAnnotation;
        this.usingAnnotation = usingAnnotation;
        usingMethodNames.add(names.fromString("summon"));
    }

    /** Records methods in a parsed file that declare {@code @forj.Using} parameters. */
    void index(JCCompilationUnit unit) {
        new com.sun.tools.javac.tree.TreeScanner() {
            @Override
            public void visitMethodDef(JCMethodDecl tree) {
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
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                super.visitMethodInvocation(node, unused); // innermost calls first
                var call = (JCMethodInvocation) node;
                if (usingMethodNames.contains(TreeInfo.name(call.meth))) {
                    new CallSite(unit, getCurrentPath(), call).complete();
                }
                return null;
            }
        }.scan(classPath, null);
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

    private void indexMembers(ClassSymbol c) {
        for (Symbol m : c.members().getSymbols()) {
            if (m instanceof MethodSymbol method && usingCount(method) > 0) {
                usingMethodNames.add(method.name);
            }
        }
    }

    // ------------------------------------------------------------------ one call

    private final class CallSite {
        private final JCCompilationUnit unit;
        private final JCMethodInvocation call;
        private final Env<AttrContext> env;
        private final JavacScope scope;
        private final TreePath path;

        CallSite(JCCompilationUnit unit, TreePath path, JCMethodInvocation call) {
            this.unit = unit;
            this.call = call;
            this.path = path;
            this.scope = (JavacScope) trees.getScope(path);
            this.env = scope.getEnv();
        }

        /** Local {@code given} variables declared before this call in its enclosing blocks. */
        private List<Symbol> localGivens() {
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

        void complete() {
            Name name = TreeInfo.name(call.meth);
            List<MethodSymbol> candidates = new ArrayList<>();
            boolean plainOverload = false;
            for (Symbol s : methodsNamed(name)) {
                MethodSymbol m = (MethodSymbol) s;
                int params = m.params().size();
                int using = usingCount(m);
                if (params == call.args.size()) {
                    plainOverload |= accepts(m);
                } else if (using > 0 && params - using == call.args.size()) {
                    candidates.add(m);
                }
            }
            if (plainOverload || candidates.isEmpty()) {
                return; // an ordinary call; javac handles it
            }
            if (candidates.size() > 1) {
                error(call, "ambiguous call: " + candidates.size() + " methods named " + name + " take using parameters");
                return;
            }
            MethodSymbol method = candidates.getFirst();
            if (!usingParamsTrail(method)) {
                error(call, "using parameters of " + method + " must come last");
                return;
            }

            Map<TypeVar, Type> bindings = inferTypeArguments(method);
            if (bindings == null) {
                return;
            }
            ListBuffer<JCExpression> given = new ListBuffer<>();
            List<VarSymbol> params = method.params();
            List<Type> paramTypes = method.type.getParameterTypes();
            for (int i = call.args.size(); i < params.size(); i++) {
                Type required = substitute(paramTypes.get(i), bindings);
                if (hasUnbound(required, method)) {
                    error(call, "cannot infer the type of using parameter " + params.get(i).name + " (" + required
                            + ") of " + method.name + "; pass type arguments explicitly, e.g. Owner.<T>" + method.name + "(...)");
                    return;
                }
                Search search = new Search();
                JCExpression found = search.find(required, 0);
                if (found == null) {
                    error(call, search.failure(required, method));
                    return;
                }
                given.append(found);
            }
            call.args = call.args.appendList(given.toList());
        }

        private List<Type> argTypes;

        /** Argument types, type checked once on copies; null where unknown (e.g. lambdas). */
        private List<Type> argTypes() {
            if (argTypes == null) {
                argTypes = new ArrayList<>();
                for (JCExpression arg : call.args) {
                    Type t = speculate(arg, false);
                    argTypes.add(t == null || t.isErroneous() ? null : t);
                }
            }
            return argTypes;
        }

        /** Could this ordinary overload take the call's arguments? Unknown counts as yes. */
        private boolean accepts(MethodSymbol m) {
            List<Type> params = m.type.getParameterTypes();
            for (int i = 0; i < params.size(); i++) {
                Type arg = argTypes().get(i);
                if (arg != null && !types.isConvertible(arg, types.erasure(params.get(i)))) {
                    return false;
                }
            }
            return true;
        }

        // ------------------------------------------------- which methods could this be?

        private Set<Symbol> methodsNamed(Name name) {
            Set<Symbol> found = new LinkedHashSet<>();
            if (call.meth instanceof JCFieldAccess select) {
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

        private void addMembers(Type site, Name name, Set<Symbol> into) {
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
            List<Type> paramTypes = method.type.getParameterTypes();
            for (int i = 0; i < call.args.size(); i++) {
                Type arg = argTypes().get(i);
                if (arg != null) {
                    unify(paramTypes.get(i), arg, bindings, tvars);
                }
            }
            return bindings;
        }

        /** Type checks a copy of {@code tree} in this call's scope, reporting nothing. */
        private Type speculate(JCTree tree, boolean asType) {
            JCTree copy = new TreeCopier<Void>(make).copy(tree);
            Env<AttrContext> scratch = env.dup(copy); // env is getScope's private copy already
            Log.DiagnosticHandler discard = log.new DiscardDiagnosticHandler();
            try {
                return asType ? attr.attribType(copy, scratch) : attr.attribExpr(copy, scratch);
            } catch (RuntimeException e) {
                return null;
            } finally {
                log.popDiagnosticHandler(discard);
            }
        }

        private void error(JCTree at, String message) {
            trees.printMessage(Diagnostic.Kind.ERROR, "forj: " + message, at, unit);
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
                    return make.Select(qualified((ClassSymbol) v.owner), v.name);
                }
                MethodSymbol m = (MethodSymbol) candidate;
                List<Type> tvars = m.type.getTypeArguments();
                Map<TypeVar, Type> bindings = new HashMap<>();
                unify(m.type.getReturnType(), required, bindings, tvars);
                Type result = substitute(m.type.getReturnType(), bindings);
                if (hasUnbound(result, m) || !types.isSubtype(result, required)) {
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
                make.at(call.pos);
                return make.Apply(com.sun.tools.javac.util.List.nil(),
                        make.Select(qualified((ClassSymbol) m.owner), m.name), args.toList());
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

    // ------------------------------------------------------------------- types

    /** Binds type variables of {@code pattern} so it matches {@code actual} (one-sided, best effort). */
    private void unify(Type pattern, Type actual, Map<TypeVar, Type> bindings, List<Type> tvars) {
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

    private Type substitute(Type t, Map<TypeVar, Type> bindings) {
        ListBuffer<Type> from = new ListBuffer<>();
        ListBuffer<Type> to = new ListBuffer<>();
        bindings.forEach((k, v) -> {
            from.append(k);
            to.append(v);
        });
        return types.subst(t, from.toList(), to.toList());
    }

    /** Still mentions one of {@code method}'s own type variables. */
    private boolean hasUnbound(Type t, MethodSymbol method) {
        List<Type> tvars = method.type.getTypeArguments();
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

    private boolean isUsing(VarSymbol p) {
        return p.attribute(usingAnnotation) != null;
    }

    private int usingCount(MethodSymbol m) {
        int n = 0;
        for (VarSymbol p : m.params()) {
            if (isUsing(p)) {
                n++;
            }
        }
        return n;
    }

    private boolean usingParamsTrail(MethodSymbol m) {
        boolean seenUsing = false;
        for (VarSymbol p : m.params()) {
            if (isUsing(p)) {
                seenUsing = true;
            } else if (seenUsing) {
                return false;
            }
        }
        return true;
    }

    private JCExpression qualified(ClassSymbol owner) {
        String[] parts = owner.getQualifiedName().toString().split("\\.");
        JCExpression e = make.Ident(names.fromString(parts[0]));
        for (int i = 1; i < parts.length; i++) {
            e = make.Select(e, names.fromString(parts[i]));
        }
        return e;
    }

    private static String describe(Symbol s) {
        return s.owner.getSimpleName() + "." + s.getSimpleName();
    }
}
