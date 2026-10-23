package forj.plugin;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Kinds.Kind;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symbol.MethodSymbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Type.WildcardType;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.comp.AttrContext;
import com.sun.tools.javac.comp.Env;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCFieldAccess;
import com.sun.tools.javac.tree.JCTree.JCMethodInvocation;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.Name;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Extension methods, for one call {@code x.name(args)} ({@link ImplicitResolver.CallSite}). */
final class Extensions {

    private final ImplicitResolver r;
    private final ImplicitResolver.CallSite site;
    private final Types types;
    private final TreeMaker make;
    private final JCMethodInvocation call;
    private final JCCompilationUnit unit;
    private final Env<AttrContext> env;

    Extensions(ImplicitResolver r, ImplicitResolver.CallSite site) {
        this.r = r;
        this.site = site;
        this.types = r.types;
        this.make = r.make;
        this.call = site.call;
        this.unit = site.unit;
        this.env = site.env;
    }

    /**
     * {@code x.name(args)} where {@code x}'s type has no method {@code name} but an extension
     * method of that name takes {@code x}: becomes {@code Owner.name(x, args)}, whose
     * {@code using} arguments the next pass fills in. Search order, nearest first: the
     * enclosing classes, {@code import static}, the type classes of the givens in scope
     * (local givens and the enclosing method's using parameters), the classes of the
     * receiver's type and its type arguments, then {@code forj.Syntax}.
     */
    ImplicitResolver.Outcome resolve(boolean report) {
        site.report = report;
        var select = (JCFieldAccess) call.meth;
        Name name = select.name;
        if (call.typeargs.nonEmpty() || ImplicitResolver.CallSite.isCastedNull(select.selected)) {
            return ImplicitResolver.Outcome.SKIPPED;   // ((Owner) null).m(...) is a static call this resolver made
        }
        Type receiver = site.speculate(select.selected, false);
        if (receiver == null || receiver.isErroneous()) {
            Type asType = site.speculate(select.selected, true);
            if (asType != null && !asType.isErroneous()) {
                return ImplicitResolver.Outcome.SKIPPED;     // `Type.name(...)`: a static call
            }
            // not typed yet (say, the result of another extension call): try again next pass
            return report ? ImplicitResolver.Outcome.SKIPPED : ImplicitResolver.Outcome.PENDING;
        }
        if (receiver.isPrimitive()) {
            receiver = types.boxedClass(receiver).type;
        }
        Set<Symbol> own = new LinkedHashSet<>();
        site.addMembers(types.skipTypeVars(receiver, false), name, own);
        own.removeIf(m -> m instanceof MethodSymbol ms && r.isExtension(ms)); // e.g. declared in the receiver's record
        if (!own.isEmpty()) {
            return ImplicitResolver.Outcome.SKIPPED;         // the type's own method wins, as in Scala
        }
        JCExpression liftedReceiver = null;
        boolean liftable = site.enabled(ImplicitResolver.LIFT_RECEIVERS) && (liftedReceiver = site.lifting.lift(select.selected, receiver)) != null;
        for (List<MethodSymbol> level : extensionLevels(name, receiver)) {
            Type recv = receiver;
            List<MethodSymbol> fits = level.stream().filter(m -> takesReceiver(m, recv)).toList();
            boolean lifted = false;
            if (fits.isEmpty() && liftable) {
                // liftReceivers: a JDK value (List, Callable) for an extension on Kind
                fits = level.stream().filter(this::takesLiftedReceiver).toList();
                lifted = !fits.isEmpty();
            }
            if (fits.size() > 1) {
                return site.error(call, "ambiguous extension methods for " + name + ": "
                        + fits.stream().map(ImplicitResolver::describe).toList());
            }
            if (fits.size() == 1) {
                MethodSymbol m = fits.getFirst();
                r.indexMembers((ClassSymbol) m.owner);   // its using parameters, if from a library
                make.at(select.pos);
                call.meth = make.Select(site.owner((ClassSymbol) m.owner), name);
                call.args = call.args.prepend(lifted ? liftedReceiver : select.selected);
                return ImplicitResolver.Outcome.RESOLVED;
            }
        }
        return ImplicitResolver.Outcome.SKIPPED;             // javac reports the missing method
    }

    /** An extension method that can be called with the receiver and this call's arguments. */
    private boolean takesReceiver(MethodSymbol m, Type receiver) {
        int params = m.params().size();
        int explicit = call.args.size() + 1;
        if (params != explicit && params - r.usingCount(m) != explicit) {
            return false;
        }
        return types.isConvertible(receiver, types.erasure(m.type.getParameterTypes().head));
    }

    /** An extension on Kind that takes this call's arguments, once the receiver is lifted. */
    private boolean takesLiftedReceiver(MethodSymbol m) {
        int params = m.params().size();
        int explicit = call.args.size() + 1;
        return (params == explicit || params - r.usingCount(m) == explicit)
                && site.lifting.isKindType(types.erasure(m.type.getParameterTypes().head));
    }

    private List<List<MethodSymbol>> extensionLevels(Name name, Type receiver) {
        List<Set<Symbol>> levels = new ArrayList<>();
        Set<Symbol> enclosing = new LinkedHashSet<>();
        for (Env<AttrContext> e = env; e != null; e = e.outer) {
            if (e.enclClass != null && e.enclClass.sym != null) {
                site.addMembers(e.enclClass.sym.type, name, enclosing);
            }
        }
        levels.add(enclosing);
        Set<Symbol> imported = new LinkedHashSet<>();
        unit.namedImportScope.getSymbolsByName(name).forEach(imported::add);
        unit.starImportScope.getSymbolsByName(name).forEach(imported::add);
        levels.add(imported);
        // like Scala's extensions on a given in scope: the type classes of the enclosing
        // method's using parameters and of local givens (`a.show()` given a Show<A>)
        Set<Symbol> inScope = new LinkedHashSet<>();
        List<Symbol> givensInScope = new ArrayList<>(site.localGivens());
        if (env.enclMethod != null && env.enclMethod.sym != null) {
            env.enclMethod.sym.params().stream().filter(r::isUsing).forEach(givensInScope::add);
        }
        for (Symbol g : givensInScope) {
            site.addMembers(g.type, name, inScope);
        }
        levels.add(inScope);
        Set<Symbol> companions = new LinkedHashSet<>();
        addReceiverClasses(receiver, name, companions, new java.util.HashSet<>());
        levels.add(companions);
        Set<Symbol> defaults = new LinkedHashSet<>();
        if (r.defaultSyntax != null) {
            site.addMembers(r.defaultSyntax.type, name, defaults);
        }
        levels.add(defaults);
        List<List<MethodSymbol>> result = new ArrayList<>();
        for (Set<Symbol> level : levels) {
            result.add(level.stream()
                    .filter(s -> s instanceof MethodSymbol m && r.isExtension(m) && (m.flags() & Flags.STATIC) != 0
                            && r.rs.isAccessible(env, m.owner.type, m))
                    .map(s -> (MethodSymbol) s)
                    .toList());
        }
        return result;
    }

    /** Extensions declared in the receiver type's classes and its type arguments' classes. */
    private void addReceiverClasses(Type t, Name name, Set<Symbol> into, Set<Symbol> seen) {
        t = types.skipTypeVars(t, false);
        if (t == null || t.tsym == null || !seen.add(t.tsym)) {
            return;
        }
        site.addMembers(t, name, into);
        for (Type arg : t.getTypeArguments()) {
            addReceiverClasses(arg instanceof WildcardType w ? w.type : arg, name, into, seen);
        }
    }
}
