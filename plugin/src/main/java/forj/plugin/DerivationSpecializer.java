package forj.plugin;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symbol.MethodSymbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.comp.AttrContext;
import com.sun.tools.javac.comp.Env;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCLambda;
import com.sun.tools.javac.tree.JCTree.JCMethodInvocation;
import com.sun.tools.javac.tree.TreeCopier;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Log;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.Names;
import java.util.HashMap;
import java.util.List;

/**
 * The specialized form of {@code derives} (see {@link Deriver}): runs before each class is
 * attributed, on the derived givens whose records javac has entered by then.
 */
final class DerivationSpecializer {

    private final ImplicitResolver r;
    private final Types types;
    private final TreeMaker make;
    private final Names names;

    DerivationSpecializer(ImplicitResolver r) {
        this.r = r;
        this.types = r.types;
        this.make = r.make;
        this.names = r.names;
    }

    /**
     * derives, specialized: for each derived given whose record javac has entered, if the type
     * class declares {@code deriveProduct} and {@code deriveField}, replace
     * {@code X.<R>product(ctor, fields...)} with
     * {@code X.<R>deriveProduct(ctor, (Out out, R value) -> { X.deriveField(out, 0, value.c0()); ... }, fields...)}.
     * {@code Out} comes from {@code deriveProduct}'s encoder parameter, so forj never names a
     * type of the library; javac's overload resolution picks each component's {@code deriveField}.
     */
    void run() {
        for (var it = r.derivations.iterator(); it.hasNext(); ) {
            Deriver.Derivation d = it.next();
            if (d.record().sym == null) {
                continue;   // not entered yet: its own class comes later
            }
            it.remove();
            try {
                specialize(d);
            } catch (RuntimeException e) {
                ImplicitResolver.trace("derives: not specializing " + d.record().name + ": " + e);
            }
        }
    }

    private void specialize(Deriver.Derivation d) {
        var recordSym = (ClassSymbol) d.record().sym;
        Env<AttrContext> env = r.enter.getEnv(recordSym);
        if (env == null || !(d.field().init instanceof JCMethodInvocation product)) {
            return;
        }
        Type typeClass = attribTypeQuietly(d.typeClass(), env);
        if (typeClass == null || !(typeClass.tsym instanceof ClassSymbol x)) {
            return;
        }
        r.indexMembers(x);
        MethodSymbol deriveProduct = null;
        boolean hasDeriveField = false;
        for (Symbol s : x.members().getSymbols()) {
            if (s instanceof MethodSymbol m && (m.flags() & Flags.STATIC) != 0) {
                if (m.name.contentEquals("deriveProduct") && m.params().size() >= 2) {
                    deriveProduct = m;
                }
                hasDeriveField |= m.name.contentEquals("deriveField");
            }
        }
        if (deriveProduct == null || !hasDeriveField || deriveProduct.type.getTypeArguments().size() != 1) {
            return;
        }
        // the encoder parameter's functional type, with deriveProduct's T as the record: (Out, R) -> ...
        Type encoderType = types.subst(deriveProduct.type.getParameterTypes().get(1),
                deriveProduct.type.getTypeArguments(), com.sun.tools.javac.util.List.of(recordSym.type));
        List<Type> inputs = r.descriptorInputs(encoderType);
        if (inputs == null || inputs.size() != 2) {
            return;
        }
        make.at(d.encoderPos());
        Name out = names.fromString("out");
        Name value = names.fromString("value");
        ListBuffer<JCTree.JCStatement> body = new ListBuffer<>();
        for (int i = 0; i < d.components().size(); i++) {
            body.append(make.Exec(make.Apply(com.sun.tools.javac.util.List.nil(),
                    make.Select(new TreeCopier<Void>(make).copy(d.typeClass()), names.fromString("deriveField")),
                    com.sun.tools.javac.util.List.of(make.Ident(out), make.Literal(com.sun.tools.javac.code.TypeTag.INT, i),
                            make.Apply(com.sun.tools.javac.util.List.nil(),
                                    make.Select(make.Ident(value), d.components().get(i).name),
                                    com.sun.tools.javac.util.List.nil())))));
        }
        JCLambda encoder = make.Lambda(com.sun.tools.javac.util.List.of(
                        make.VarDef(make.Modifiers(Flags.PARAMETER), out, r.typeTree(inputs.get(0)), null),
                        make.VarDef(make.Modifiers(Flags.PARAMETER), value, r.typeTree(inputs.get(1)), null)),
                make.Block(0, body.toList()));
        encoder.pos = d.encoderPos();
        product.meth = make.Select(new TreeCopier<Void>(make).copy(d.typeClass()), deriveProduct.name);
        product.args = product.args.tail.prepend(encoder).prepend(product.args.head);
        ImplicitResolver.trace("derives: specialized " + d.record().name + " for " + x + ": " + product);
    }

    private Type attribTypeQuietly(JCExpression tree, Env<AttrContext> env) {
        JCTree copy = new TreeCopier<Void>(make).copy(tree);
        Object saved = r.swapArgumentCache(new HashMap<>());
        Log.DiagnosticHandler discard = r.log.new DiscardDiagnosticHandler();
        try {
            Type t = r.attr.attribType(copy, env.dup(copy));
            return t == null || t.isErroneous() ? null : t;
        } finally {
            r.log.popDiagnosticHandler(discard);
            r.swapArgumentCache(saved);
        }
    }
}
