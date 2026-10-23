package forj.plugin;

import com.sun.source.util.TreePath;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Kinds.Kind;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symbol.MethodSymbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Type.TypeVar;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.tree.JCTree.JCAssign;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCMethodInvocation;
import com.sun.tools.javac.tree.JCTree.JCReturn;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.tree.TreeInfo;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.Names;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Moving values between JDK types and {@code Kind}, for one call ({@link ImplicitResolver.CallSite}):
 * <ul>
 *   <li>the comprehension plumbing: {@code forjLower(k)} calls the {@code @Lower} method for
 *       {@code k}'s witness, {@code forjLift(k)} of a {@code Kind} calls the identity, and a call
 *       returning a {@code Kind} is narrowed to the type it stands for where assigned or returned;</li>
 *   <li>the opt-in lifting of {@code forj.Language}: {@code liftArguments} wraps JDK values passed
 *       to {@code Kind} parameters, {@code liftReceivers} ({@link Extensions}) wraps a JDK receiver,
 *       and {@code liftResults} unwraps a {@code Kind} assigned to a JDK type.</li>
 * </ul>
 */
final class Lifting {

    private final ImplicitResolver r;
    private final ImplicitResolver.CallSite site;
    private final Types types;
    private final TreeMaker make;
    private final Names names;
    private final JCMethodInvocation call;
    private final JCCompilationUnit unit;
    private final TreePath path;

    Lifting(ImplicitResolver r, ImplicitResolver.CallSite site) {
        this.r = r;
        this.site = site;
        this.types = r.types;
        this.make = r.make;
        this.names = r.names;
        this.call = site.call;
        this.unit = site.unit;
        this.path = site.path;
    }

    /** liftArguments: wraps each JDK value passed where {@code method} expects a Kind. */
    boolean liftArguments(MethodSymbol method) {
        ListBuffer<JCExpression> args = new ListBuffer<>();
        boolean changed = false;
        for (int i = 0; i < call.args.size(); i++) {
            JCExpression arg = call.args.get(i);
            Type type = site.argTypes().get(i);
            Type declared = site.paramFor(method, i);
            Type param = declared == null ? null : types.erasure(declared);
            if (type != null && param != null && isKindType(param) && !types.isConvertible(type, param)) {
                JCExpression lifted = lift(arg, type);
                if (lifted != null) {
                    arg = lifted;
                    changed = true;
                }
            }
            args.append(arg);
        }
        if (changed) {
            call.args = args.toList();
        }
        return changed;
    }

    boolean isKindType(Type erased) {
        return r.kindSymbol != null && types.asSuper(erased, r.kindSymbol) != null;
    }

    /**
     * {@code expr} (of JDK type {@code type}) wrapped by the one {@code r.forjLift} overload
     * that takes it, with explicit type arguments ({@code forj.For.<Integer>forjLift(xs)}),
     * so javac doesn't treat it as a poly expression cached by position. Null when no
     * overload takes it, or more than one does.
     */
    JCExpression lift(JCExpression expr, Type type) {
        if (type == null || type.isErroneous() || isKindType(type)) {
            return null;
        }
        MethodSymbol found = null;
        for (Symbol s : liftCandidates()) {
            if (!(s instanceof MethodSymbol m) || m.params().size() != 1 || (m.flags() & Flags.STATIC) == 0) {
                continue;
            }
            Type param = types.erasure(m.type.getParameterTypes().head);
            if (isKindType(param) || !types.isConvertible(type, param)) {
                continue;   // the identity overloads, or one for another type
            }
            if (found != null && found != m) {
                return null; // ambiguous: say, a class that is both a List and a Callable
            }
            found = m;
        }
        if (found == null) {
            return null;
        }
        Map<TypeVar, Type> bindings = new HashMap<>();
        List<Type> tvars = found.type.getTypeArguments();
        r.unify(found.type.getParameterTypes().head, type, bindings, tvars);
        ListBuffer<JCExpression> typeArgs = new ListBuffer<>();
        for (Type tv : tvars) {
            Type bound = bindings.get((TypeVar) tv);
            if (bound == null) {
                return null;
            }
            typeArgs.append(r.typeTree(bound));
        }
        make.at(expr.pos);
        return make.Apply(typeArgs.toList(), make.Select(site.owner((ClassSymbol) found.owner), found.name),
                com.sun.tools.javac.util.List.of(expr));
    }

    /** {@code r.forjLift} overloads in scope: forj's own and any statically imported. */
    private Set<Symbol> liftCandidates() {
        Set<Symbol> found = new LinkedHashSet<>();
        if (r.forClass != null) {
            r.forClass.members().getSymbolsByName(r.forjLift).forEach(found::add);
        }
        unit.namedImportScope.getSymbolsByName(r.forjLift).forEach(found::add);
        unit.starImportScope.getSymbolsByName(r.forjLift).forEach(found::add);
        return found;
    }

    /** How a JDK target type is reached from a Kind: its @Lower method, applied with these bindings. */
    record Lowering(MethodSymbol method, Type kind, Map<TypeVar, Type> bindings) {}

    /** The @Lower method returning {@code target}'s type (Callable<User> from Kind<Callable, User>), if any. */
    Lowering lowering(Type target) {
        if (target == null || target.isErroneous() || isKindType(target)) {
            return null;
        }
        for (Symbol s : lowerCandidates()) {
            if (!(s instanceof MethodSymbol m) || m.params().size() != 1
                    || !types.isSameType(types.erasure(m.type.getReturnType()), types.erasure(target))) {
                continue;
            }
            Map<TypeVar, Type> bindings = new HashMap<>();
            List<Type> tvars = m.type.getTypeArguments();
            r.unify(m.type.getReturnType(), target, bindings, tvars);
            if (tvars.stream().allMatch(tv -> bindings.containsKey((TypeVar) tv))) {
                return new Lowering(m, r.substitute(m.type.getParameterTypes().head, bindings), bindings);
            }
        }
        return null;
    }

    /**
     * {@code forjLift(e)} where {@code e} already is a {@code Kind}: call the one identity
     * method instead. With a generic {@code e} (say {@code either.fold(...)}) javac can't
     * choose between the {@code r.forjLift} overloads.
     */
    ImplicitResolver.Outcome liftKind() {
        Type arg = site.argTypes().getFirst();
        if (arg == null) {
            return ImplicitResolver.Outcome.PENDING;
        }
        if (types.asSuper(arg, r.kindSymbol) == null) {
            return ImplicitResolver.Outcome.SKIPPED;
        }
        make.at(call.meth.pos);
        call.meth = make.Select(site.owner(r.forClass), names.fromString("forjKind"));
        return ImplicitResolver.Outcome.RESOLVED;
    }

    /**
     * Wraps a call returning a {@code Kind} in {@code forj.For.forjNarrow(...)}, so its
     * value can be assigned or returned as the type it stands for ({@code IO<Unit>}).
     */
    boolean narrow() {
        Name name = TreeInfo.name(call.meth);
        boolean returnsKind = false;
        for (Symbol s : site.methodsNamed(name)) {
            if (s instanceof MethodSymbol m && m.params().size() == call.args.size() && m.owner != r.forClass) {
                returnsKind |= r.returnsKind(m);   // forj's own plumbing is never narrowed
            }
        }
        if (!returnsKind) {
            return false;
        }
        make.at(call.meth.pos);
        JCExpression wrapped = make.Apply(com.sun.tools.javac.util.List.nil(),
                make.Select(site.owner(r.forClass), names.fromString("forjNarrow")),
                com.sun.tools.javac.util.List.of(call));
        Lowering lowering = site.enabled(ImplicitResolver.LIFT_RESULTS) ? lowering(site.targetType()) : null;
        if (lowering != null) {
            // liftResults: a JDK target gets the value unwrapped, forj.For.<User>lowerCallable(call)
            ListBuffer<JCExpression> typeArgs = new ListBuffer<>();
            for (Type tv : lowering.method().type.getTypeArguments()) {
                typeArgs.append(r.typeTree(lowering.bindings().get((TypeVar) tv)));
            }
            make.at(call.meth.pos);
            wrapped = make.Apply(typeArgs.toList(),
                    make.Select(site.owner((ClassSymbol) lowering.method().owner), lowering.method().name),
                    com.sun.tools.javac.util.List.of(call));
        }
        switch (path.getParentPath().getLeaf()) {
            case JCVariableDecl v -> v.init = wrapped;
            case JCReturn r -> r.expr = wrapped;
            case JCAssign a -> a.rhs = wrapped;
            default -> {
                return false;
            }
        }
        return true;
    }

    /**
     * {@code forjLower(k)} where {@code k} is a {@code Kind<W, A>} for a concrete witness
     * {@code W} with a {@code @Lower} method: call that method instead, so the result is a
     * plain {@code List<A>}, {@code Optional<A>}, ... rather than a {@code Kind}.
     */
    ImplicitResolver.Outcome lower() {
        Type arg = site.argTypes().getFirst();
        if (arg == null) {
            return ImplicitResolver.Outcome.PENDING;
        }
        Type kind = r.kindSymbol == null ? null : types.asSuper(arg, r.kindSymbol);
        if (kind == null || kind.getTypeArguments().isEmpty()) {
            return ImplicitResolver.Outcome.SKIPPED;
        }
        Type witness = kind.getTypeArguments().head;
        if (witness instanceof TypeVar) {
            return ImplicitResolver.Outcome.SKIPPED; // generic F: the identity forjLower is right
        }
        for (Symbol s : lowerCandidates()) {
            if (s instanceof MethodSymbol m && m.params().size() == 1) {
                Type param = types.asSuper(m.type.getParameterTypes().head, r.kindSymbol);
                if (param != null && param.getTypeArguments().nonEmpty()
                        && types.isSameType(types.erasure(param.getTypeArguments().head), types.erasure(witness))) {
                    make.at(call.meth.pos);
                    call.meth = make.Select(site.owner((ClassSymbol) m.owner), m.name);
                    return ImplicitResolver.Outcome.RESOLVED;
                }
            }
        }
        return ImplicitResolver.Outcome.SKIPPED;
    }

    private List<Symbol> lowerCandidates() {
        List<Symbol> found = new ArrayList<>();
        if (r.lowerAnnotation == null) {
            return found;
        }
        List<Symbol> scope = new ArrayList<>();
        if (r.forClass != null) {
            r.forClass.members().getSymbols().forEach(scope::add);
        }
        unit.namedImportScope.getSymbols().forEach(scope::add);
        for (Symbol s : unit.starImportScope.getSymbols()) {
            if (s.kind == Kind.MTH) {
                scope.add(s);
            }
        }
        for (Symbol s : scope) {
            if (s.kind == Kind.MTH && s.attribute(r.lowerAnnotation) != null) {
                found.add(s);
            }
        }
        return found;
    }
}
