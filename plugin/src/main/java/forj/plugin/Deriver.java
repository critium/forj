package forj.plugin;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCAnnotation;
import com.sun.tools.javac.tree.JCTree.JCAssign;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCFieldAccess;
import com.sun.tools.javac.tree.JCTree.JCMethodInvocation;
import com.sun.tools.javac.tree.JCTree.JCNewArray;
import com.sun.tools.javac.tree.JCTree.JCPrimitiveTypeTree;
import com.sun.tools.javac.tree.JCTree.JCTypeParameter;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.tree.TreeCopier;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.tree.TreeScanner;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.Names;
import java.util.ArrayList;
import java.util.Map;

/**
 * {@code derives}: for a record annotated {@code @forj.Derives({X.class, ...})} (what
 * {@code record R(...) derives X} becomes), adds one {@code given X<R>} per type class, built
 * through the type class's {@code product}/{@code field} convention:
 *
 * <pre>{@code
 * record User(long id, String name) derives Codec { }
 *
 * // adds, in User:
 * @forj.Given public static final Codec<User> codec = Codec.<User>product(
 *         (Object[] a) -> new User((long) a[0], (String) a[1]),
 *         Codec.<User, java.lang.Long>field("id", (User r) -> r.id()),
 *         Codec.<User, String>field("name", (User r) -> r.name()));
 * }</pre>
 *
 * A generic record gets a given method instead, taking {@code using X<P>} for each type
 * parameter. Each {@code field(...)} call takes {@code using X<F>}, which the resolver fills in
 * like any other, so givens in scope apply to the components. Runs at parse time, before javac
 * enters the record's members, so the given is an ordinary member by the time javac sees it.
 * {@link ImplicitResolver} later swaps the initializer for the specialized form when the type
 * class declares {@code deriveProduct}/{@code deriveField} (non-generic records only, for now).
 */
final class Deriver {

    /**
     * One derived given, for the resolver: which record, which type class, the field added, its
     * components, and a position reserved for a specialized encoder lambda.
     */
    record Derivation(JCClassDecl record, JCExpression typeClass, JCVariableDecl field,
                      java.util.List<JCVariableDecl> components, int encoderPos) {}

    private final TreeMaker make;
    private final Names names;
    private final JCCompilationUnit unit;
    private final java.util.List<Derivation> derivations;
    private int fresh;   // offset for unique positions (javac caches argument types by position)

    Deriver(Context context, JCCompilationUnit unit, java.util.List<Derivation> derivations) {
        this.make = TreeMaker.instance(context);
        this.names = Names.instance(context);
        this.unit = unit;
        this.derivations = derivations;
    }

    void run() {
        new TreeScanner() {
            @Override
            public void visitClassDef(JCClassDecl tree) {
                super.visitClassDef(tree);
                if ((tree.mods.flags & Flags.RECORD) != 0) {
                    for (JCExpression typeClass : derivesOf(tree)) {
                        derive(tree, typeClass);
                    }
                }
            }
        }.scan(unit);
    }

    /** The type classes in the record's {@code @Derives} annotation. */
    private java.util.List<JCExpression> derivesOf(JCClassDecl record) {
        java.util.List<JCExpression> found = new ArrayList<>();
        for (JCAnnotation a : record.mods.annotations) {
            String type = a.annotationType.toString();
            if (!type.equals("forj.Derives") && !type.equals("Derives")) {
                continue;
            }
            for (JCExpression arg : a.args) {
                JCExpression value = arg instanceof JCAssign assign ? assign.rhs : arg;
                List<JCExpression> elems = value instanceof JCNewArray array ? array.elems : List.of(value);
                for (JCExpression e : elems) {
                    if (e instanceof JCFieldAccess literal && literal.name == names._class) {
                        found.add(literal.selected);
                    }
                }
            }
        }
        return found;
    }

    private void derive(JCClassDecl record, JCExpression typeClass) {
        java.util.List<JCVariableDecl> components = new ArrayList<>();
        for (JCTree def : record.defs) {
            if (def instanceof JCVariableDecl v && (v.mods.flags & Flags.RECORD) != 0) {
                components.add(v);
            }
        }
        int pos = record.pos;
        make.at(pos);
        JCExpression init = product(record, typeClass, components);
        Name name = names.fromString(decapitalize(simpleName(typeClass)));
        JCTree member;
        if (record.typarams.isEmpty()) {
            JCVariableDecl field = make.VarDef(
                    make.Modifiers(Flags.PUBLIC | Flags.STATIC | Flags.FINAL, annotations()),
                    name, make.TypeApply(copy(typeClass), List.of(recordType(record))), init);
            derivations.add(new Derivation(record, typeClass, field, components, make.pos + 1 + fresh++));
            member = field;
        } else {
            // <A> X<R<A>> name(@forj.Using X<A> a$, ...) { return X.<R<A>>product(...); }
            ListBuffer<JCVariableDecl> params = new ListBuffer<>();
            for (JCTypeParameter p : record.typarams) {
                params.append(make.VarDef(
                        make.Modifiers(Flags.PARAMETER, List.of(annotation("forj", "Using"))),
                        names.fromString(p.name + "$" + name),
                        make.TypeApply(copy(typeClass), List.of(make.Ident(p.name))), null));
            }
            member = make.MethodDef(
                    make.Modifiers(Flags.PUBLIC | Flags.STATIC, annotations()),
                    name, make.TypeApply(copy(typeClass), List.of(recordType(record))),
                    copy(record.typarams), params.toList(), List.nil(),
                    make.Block(0, List.of(make.Return(init))), null);
        }
        record.defs = record.defs.append(member);
    }

    /** {@code X.<R>product((Object[] a) -> new R(...), X.<R, F>field("c", (R r) -> r.c()), ...)}. */
    private JCExpression product(JCClassDecl record, JCExpression typeClass, java.util.List<JCVariableDecl> components) {
        Name array = names.fromString("a");
        ListBuffer<JCExpression> ctorArgs = new ListBuffer<>();
        for (int i = 0; i < components.size(); i++) {
            ctorArgs.append(make.TypeCast(copy(components.get(i).vartype),
                    make.Indexed(make.Ident(array), make.Literal(TypeTag.INT, i))));
        }
        JCTree.JCLambda constructor = lambda(
                List.of(param(array, make.TypeArray(qualified("java", "lang", "Object")))),
                make.NewClass(null, List.nil(), recordType(record), ctorArgs.toList(), null));
        ListBuffer<JCExpression> args = new ListBuffer<>();
        args.append(constructor);
        Name r = names.fromString("r");
        for (JCVariableDecl c : components) {
            JCTree.JCLambda getter = lambda(List.of(param(r, recordType(record))),
                    make.Apply(List.nil(), make.Select(make.Ident(r), c.name), List.nil()));
            args.append(make.Apply(List.of(recordType(record), boxed(c.vartype)),
                    make.Select(copy(typeClass), names.fromString("field")),
                    List.of(make.Literal(c.name.toString()), getter)));
        }
        return make.Apply(List.of(recordType(record)), make.Select(copy(typeClass), names.fromString("product")),
                args.toList());
    }

    /**
     * An explicitly typed lambda at a position of its own, every node in it included. javac
     * caches argument types by position (a lambda's start is its first parameter's), so getters
     * left at the record's position look like one lambda: the second gets the first's type.
     */
    private JCTree.JCLambda lambda(List<JCVariableDecl> params, JCTree body) {
        int pos = make.pos + 1 + fresh++;
        JCTree.JCLambda l = make.Lambda(params, body);
        new TreeScanner() {
            @Override
            public void scan(JCTree tree) {
                if (tree != null) {
                    tree.pos = pos;
                    super.scan(tree);
                }
            }
        }.scan(l);
        return l;
    }

    private JCVariableDecl param(Name name, JCExpression type) {
        return make.VarDef(make.Modifiers(Flags.PARAMETER), name, type, null);
    }

    /** {@code R}, or {@code R<A, B>} for a generic record. */
    private JCExpression recordType(JCClassDecl record) {
        if (record.typarams.isEmpty()) {
            return make.Ident(record.name);
        }
        ListBuffer<JCExpression> args = new ListBuffer<>();
        for (JCTypeParameter p : record.typarams) {
            args.append(make.Ident(p.name));
        }
        return make.TypeApply(make.Ident(record.name), args.toList());
    }

    private static final Map<TypeTag, String> BOXES = Map.of(
            TypeTag.BOOLEAN, "Boolean", TypeTag.BYTE, "Byte", TypeTag.SHORT, "Short", TypeTag.CHAR, "Character",
            TypeTag.INT, "Integer", TypeTag.LONG, "Long", TypeTag.FLOAT, "Float", TypeTag.DOUBLE, "Double");

    /** The component's type as a type argument: {@code long} becomes {@code java.lang.Long}. */
    private JCExpression boxed(JCExpression type) {
        if (type instanceof JCPrimitiveTypeTree p && BOXES.containsKey(p.typetag)) {
            return qualified("java", "lang", BOXES.get(p.typetag));
        }
        return copy(type);
    }

    private List<JCAnnotation> annotations() {
        // the constructor lambda casts a[i] to each component's type, unchecked for generic ones
        return List.of(annotation("forj", "Given"),
                make.Annotation(qualified("java", "lang", "SuppressWarnings"), List.of(make.Literal("unchecked"))));
    }

    private JCAnnotation annotation(String pkg, String name) {
        return make.Annotation(qualified(pkg, name), List.nil());
    }

    private JCExpression qualified(String... parts) {
        JCExpression e = make.Ident(names.fromString(parts[0]));
        for (int i = 1; i < parts.length; i++) {
            e = make.Select(e, names.fromString(parts[i]));
        }
        return e;
    }

    private <T extends JCTree> T copy(T tree) {
        return new TreeCopier<Void>(make).copy(tree);
    }

    private <T extends JCTree> List<T> copy(List<T> trees) {
        return new TreeCopier<Void>(make).copy(trees);
    }

    private static String simpleName(JCExpression typeClass) {
        return typeClass instanceof JCFieldAccess f ? f.name.toString() : typeClass.toString();
    }

    private static String decapitalize(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }
}
