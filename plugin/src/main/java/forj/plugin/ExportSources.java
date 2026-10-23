package forj.plugin;

import com.sun.tools.javac.parser.ParserFactory;
import com.sun.tools.javac.parser.ScannerFactory;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCArrayTypeTree;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCFieldAccess;
import com.sun.tools.javac.tree.JCTree.JCIdent;
import com.sun.tools.javac.tree.JCTree.JCImport;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCTypeApply;
import com.sun.tools.javac.tree.JCTree.JCTypeParameter;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.tree.TreeScanner;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Log;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Writes sources the way the plugin's first stage rewrites them, for tools that parse
 * {@code .java} files without the plugin: {@code javadoc} above all. The output is what javac
 * parses with {@code -Xplugin:Forj} before desugaring: {@code F<A>} is
 * {@code forj.Kind<F, A>}, {@code using} clauses are {@code @forj.Using} parameters,
 * {@code given} is {@code @forj.Given}, comprehensions are lambdas. Declarations are exactly
 * what plain Java callers see, and method bodies parse (javadoc doesn't type check them).
 *
 * <p>Doc links name methods the way the source declares them, without the using parameters:
 * {@code {@link #encode(Object, OutputStream)}} for {@code encode(A value, OutputStream out)(using
 * Codec<A> codec)}. Those links (and {@code @see}) get the using parameters' types appended,
 * {@code #encode(Object, OutputStream, Codec)}, so they resolve against the exported signature.
 *
 * <pre>{@code
 * java --add-exports=jdk.compiler/com.sun.tools.javac.{parser,tree,util}=ALL-UNNAMED \
 *      -cp forj-plugin.jar forj.plugin.ExportSources <out dir> <source root>...
 * }</pre>
 *
 * Files that use no forj syntax are copied as they are; other files under the roots are
 * ignored. A varargs parameter followed by a using clause stays an array: moving it last is
 * the Desugarer's job, which runs after parsing.
 */
public final class ExportSources {

    private ExportSources() {}

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("usage: ExportSources <out dir> <source root>...");
            System.exit(2);
        }
        Path out = Path.of(args[0]);
        List<Path> roots = Stream.of(args).skip(1).map(Path::of).filter(Files::isDirectory).toList();
        StringWriter errors = new StringWriter();
        int count = export(out, roots, new PrintWriter(errors));
        if (!errors.toString().isEmpty()) {
            System.err.print(errors);
        }
        System.out.println("forj: exported " + count + " sources to " + out);
    }

    /** Rewrites every {@code .java} file under {@code roots} into {@code out}; returns how many. */
    static int export(Path out, List<Path> roots, PrintWriter errors) throws IOException {
        Context context = new Context();
        // the parser asks for one; the public API's will do, so no more javac internals are needed
        context.put(javax.tools.JavaFileManager.class,
                javax.tools.ToolProvider.getSystemJavaCompiler().getStandardFileManager(null, null, null));
        // the real compile reports errors in these files; here they would only repeat them
        context.put(Log.errKey, errors);
        ScannerFactory scanners = ScannerFactory.instance(context);
        Log log = Log.instance(context);
        Map<Path, String> exported = new LinkedHashMap<>();
        for (Path root : roots) {
            List<Path> sources;
            try (Stream<Path> files = Files.walk(root)) {
                sources = files.filter(f -> f.toString().endsWith(".java") && Files.isRegularFile(f)).toList();
            }
            for (Path source : sources) {
                String text = Files.readString(source);
                SourceRewriter.Result rewritten = SourceRewriter.rewrite(text, scanners, log);
                exported.put(out.resolve(root.relativize(source).toString()),
                        rewritten == null ? text : new String(rewritten.source()));
            }
        }
        Links links = new Links(context);
        exported.values().forEach(links::index);
        for (var e : exported.entrySet()) {
            Files.createDirectories(e.getKey().getParent());
            Files.writeString(e.getKey(), links.rewrite(e.getValue()));
        }
        return exported.size();
    }

    // -------------------------------------------------------------------- links

    /** Methods with using parameters, by class and method name, and the links that name them. */
    private static final class Links {
        /** A method as a doc link names it: ordinary parameter types, then the using ones to append. */
        private record Method(List<String> ordinary, List<String> using) {}

        /** Matches {@code {@link Cls#name(args)}}, {@code {@linkplain ...}} and {@code @see Cls#name(args)}. */
        private static final Pattern LINK = Pattern.compile(
                "(\\{@link(?:plain)?\\s+|@see\\s+)([\\w.]*)#(\\w+)\\(([^)]*)\\)");

        private final ParserFactory parsers;
        private final Map<String, List<Method>> methods = new HashMap<>();   // "Cls#name" -> overloads

        Links(Context context) {
            this.parsers = ParserFactory.instance(context);
        }

        /** Records the methods of one exported file that have using parameters. */
        void index(String source) {
            JCCompilationUnit unit;
            try {
                unit = parsers.newParser(source, false, false, false).parseCompilationUnit();
            } catch (RuntimeException e) {
                return;   // a file javac can't parse: its links stay as written
            }
            Map<String, String> imports = new HashMap<>();
            for (JCTree def : unit.defs) {
                if (def instanceof JCImport imp && !imp.staticImport && imp.qualid.name.toString().indexOf('*') < 0) {
                    imports.put(imp.qualid.name.toString(), imp.qualid.toString());
                }
            }
            new TreeScanner() {
                private final List<Set<String>> typeVars = new ArrayList<>();

                @Override
                public void visitClassDef(JCClassDecl tree) {
                    typeVars.add(names(tree.typarams));
                    for (JCTree member : tree.defs) {
                        if (member instanceof JCMethodDecl m) {
                            add(tree.name.toString(), m);
                        }
                    }
                    super.visitClassDef(tree);
                    typeVars.removeLast();
                }

                private void add(String owner, JCMethodDecl m) {
                    Set<String> vars = new HashSet<>(names(m.typarams));
                    typeVars.forEach(vars::addAll);
                    List<String> ordinary = new ArrayList<>();
                    List<String> using = new ArrayList<>();
                    for (JCVariableDecl p : m.params) {
                        boolean isUsing = p.mods.annotations.stream()
                                .anyMatch(a -> a.annotationType.toString().matches("(forj\\.)?Using"));
                        if (isUsing) {
                            using.add(qualify(erasure(p.vartype, vars), imports));
                        } else {
                            ordinary.add(erasure(p.vartype, vars));
                        }
                    }
                    if (!using.isEmpty()) {
                        methods.computeIfAbsent(owner + "#" + m.name, k -> new ArrayList<>()).add(new Method(ordinary, using));
                    }
                }
            }.scan(unit);
        }

        /**
         * Appends the using parameters' types to each link that names a method with using
         * parameters by its ordinary ones. A link that already gives every parameter, or matches
         * no such method or more than one, stays as written.
         */
        String rewrite(String source) {
            if (methods.isEmpty() || (!source.contains("@link") && !source.contains("@see"))) {
                return source;
            }
            Set<String> classes = declaredClasses(source);
            Matcher m = LINK.matcher(source);
            StringBuilder out = new StringBuilder();
            while (m.find()) {
                String owner = m.group(2);
                String simpleOwner = owner.substring(owner.lastIndexOf('.') + 1);
                List<String> args = m.group(4).isBlank() ? List.of()
                        : Stream.of(m.group(4).split(",")).map(String::strip).toList();
                List<Method> candidates = new ArrayList<>();
                for (String cls : simpleOwner.isEmpty() ? classes : Set.of(simpleOwner)) {
                    for (Method method : methods.getOrDefault(cls + "#" + m.group(3), List.of())) {
                        if (matches(method.ordinary(), args)) {
                            candidates.add(method);
                        }
                    }
                }
                String replacement = m.group();
                if (candidates.size() == 1) {
                    List<String> all = new ArrayList<>(args);
                    all.addAll(candidates.getFirst().using());
                    replacement = m.group(1) + owner + "#" + m.group(3) + "(" + String.join(", ", all) + ")";
                }
                m.appendReplacement(out, Matcher.quoteReplacement(replacement));
            }
            m.appendTail(out);
            return out.toString();
        }

        /** Link argument types against declared ones: by simple name, and a type variable fits anything. */
        private static boolean matches(List<String> declared, List<String> args) {
            if (declared.size() != args.size()) {
                return false;
            }
            for (int i = 0; i < declared.size(); i++) {
                String d = declared.get(i);
                String a = simpleName(args.get(i).replace("...", "[]"));
                if (!d.equals("?") && !simpleName(d).equals(a)) {
                    return false;
                }
            }
            return true;
        }

        /** The type as a link writes it: no type arguments; {@code ?} for a type variable. */
        private static String erasure(JCExpression type, Set<String> typeVars) {
            return switch (type) {
                case JCArrayTypeTree a -> erasure(a.elemtype, typeVars) + "[]";
                case JCTypeApply t -> erasure(t.clazz, typeVars);
                case JCIdent id when typeVars.contains(id.name.toString()) -> "?";
                case JCTree.JCAnnotatedType at -> erasure(at.underlyingType, typeVars);
                default -> type.toString();
            };
        }

        /** A simple type name as its file imports it, so the link resolves from any file. */
        private static String qualify(String type, Map<String, String> imports) {
            return type.contains(".") ? type : imports.getOrDefault(type, type);
        }

        private static String simpleName(String type) {
            return type.substring(type.lastIndexOf('.') + 1);
        }

        private static Set<String> names(List<JCTypeParameter> params) {
            Set<String> names = new HashSet<>();
            params.forEach(p -> names.add(p.name.toString()));
            return names;
        }

        private static final Pattern CLASS = Pattern.compile("\\b(?:class|interface|record|enum)\\s+(\\w+)");

        /** Classes declared in a file: where {@code #name} links without a class look. */
        private static Set<String> declaredClasses(String source) {
            Set<String> found = new HashSet<>();
            Matcher m = CLASS.matcher(source);
            while (m.find()) {
                found.add(m.group(1));
            }
            return found;
        }
    }
}
