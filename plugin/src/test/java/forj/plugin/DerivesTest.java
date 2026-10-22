package forj.plugin;

import static forj.plugin.ImplicitsTest.compile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** derives: generated givens through a type class's product/field convention. */
class DerivesTest {

    /** A test-only type class with the generic convention: shows a value, and rebuilds it through the constructor. */
    static final String DEMO = """
            package lib;
            import java.util.function.Function;
            public interface Demo<A> {
                String show(A a);
                A rebuild(A a);

                record Field<T, F>(String name, Function<T, F> getter, Demo<F> demo) {}

                static <T, F> Field<T, F> field(String name, Function<T, F> getter)(using Demo<F> demo) {
                    return new Field<>(name, getter, demo);
                }

                @SafeVarargs
                static <T> Demo<T> product(Function<Object[], T> constructor, Field<T, ?>... fields) {
                    return new Demo<>() {
                        public String show(T t) {
                            StringBuilder out = new StringBuilder("{");
                            for (Field<T, ?> f : fields) {
                                out.append(out.length() > 1 ? ", " : "").append(f.name()).append('=').append(showOne(f, t));
                            }
                            return out.append('}').toString();
                        }
                        public T rebuild(T t) {
                            Object[] a = new Object[fields.length];
                            for (int i = 0; i < fields.length; i++) a[i] = rebuildOne(fields[i], t);
                            return constructor.apply(a);
                        }
                    };
                }

                private static <T, F> String showOne(Field<T, F> f, T t) { return f.demo().show(f.getter().apply(t)); }
                private static <T, F> F rebuildOne(Field<T, F> f, T t) { return f.demo().rebuild(f.getter().apply(t)); }

                static <A> Demo<A> of(Function<A, String> show) {
                    return new Demo<>() {
                        public String show(A a) { return show.apply(a); }
                        public A rebuild(A a) { return a; }
                    };
                }

                given Demo<Long> longs = of(n -> n + "L");
                given Demo<Integer> ints = of(n -> "" + n);
                given Demo<String> strings = of(s -> '"' + s + '"');
            }
            """;

    /** A test-only type class that also has the specialized convention: deriveProduct and deriveField overloads. */
    static final String FAST = """
            package lib;
            import java.util.function.Function;
            public interface Fast<A> {
                void write(StringBuilder out, A a);

                record Field<T, F>(String name, Function<T, F> getter, Fast<F> fast) {}

                static <T, F> Field<T, F> field(String name, Function<T, F> getter)(using Fast<F> fast) {
                    return new Field<>(name, getter, fast);
                }

                /** The generic form: walks the fields. */
                @SafeVarargs
                static <T> Fast<T> product(Function<Object[], T> constructor, Field<T, ?>... fields) {
                    return (out, t) -> {
                        out.append("G:");
                        for (Field<T, ?> f : fields) writeOne(out, f, t);
                    };
                }

                private static <T, F> void writeOne(StringBuilder out, Field<T, F> f, T t) {
                    out.append(f.name()).append('=');
                    f.fast().write(out, f.getter().apply(t));
                    out.append(' ');
                }

                /** The specialized form: forj generates the encoder. */
                final class Out {
                    final StringBuilder sb;
                    final String[] names;
                    Out(StringBuilder sb, String[] names) { this.sb = sb; this.names = names; }
                }

                interface Encoder<T> { void encode(Out out, T value); }

                @SafeVarargs
                static <T> Fast<T> deriveProduct(Function<Object[], T> constructor, Encoder<T> encoder, Field<T, ?>... fields) {
                    String[] names = new String[fields.length];
                    for (int i = 0; i < fields.length; i++) names[i] = fields[i].name();
                    return (out, t) -> encoder.encode(new Out(out, names), t);
                }

                static void deriveField(Out out, int i, long v) { out.sb.append(out.names[i]).append("=long:").append(v).append(' '); }
                static void deriveField(Out out, int i, String v) { out.sb.append(out.names[i]).append("=string:").append(v).append(' '); }
                static <A> void deriveField(Out out, int i, A v)(using Fast<A> fast) {
                    out.sb.append(out.names[i]).append("=generic:");
                    fast.write(out.sb, v);
                    out.sb.append(' ');
                }

                given Fast<Long> longs = (out, n) -> out.append(n);
                given Fast<Integer> ints = (out, n) -> out.append(n);
                given Fast<String> strings = (out, s) -> out.append(s);
                given Fast<java.time.Instant> instants = (out, t) -> out.append(t.getEpochSecond());
            }
            """;

    @Test
    void aTypeClassWithDeriveFieldGetsAStraightLineEncoder() throws Exception {
        var c = compile(Map.of("lib/Fast.java", FAST, "app/App.java", """
                package app;
                import java.time.Instant;
                import java.util.List;
                import lib.Fast;
                public class App {
                    record User(long id, String name, int age, Instant at) derives Fast {}
                    record Box<A>(A value) derives Fast {}                        // generic: the product form
                    public static Object run() {
                        StringBuilder user = new StringBuilder();
                        User.fast.write(user, new User(1, "ana", 30, Instant.ofEpochSecond(42)));
                        StringBuilder box = new StringBuilder();
                        Fast<Box<String>> boxes = forj.Implicits.summon();
                        boxes.write(box, new Box<>("x"));
                        return List.of(user.toString(), box.toString());
                    }
                }
                """));
        // long and String took their own overloads, int widened to long, Instant went generic
        assertEquals(List.of("id=long:1 name=string:ana age=long:30 at=generic:42 ", "G:value=x "),
                c.call("app.App", "run"));
    }

    @Test
    void aRecordGetsADerivedGiven() throws Exception {
        var c = compile(Map.of("lib/Demo.java", DEMO, "app/App.java", """
                package app;
                import java.util.List;
                import lib.Demo;
                public class App {
                    record User(long id, String name) derives Demo {}
                    public static Object run() {
                        User ana = new User(1, "ana");
                        return List.of(User.demo.show(ana), User.demo.rebuild(ana).equals(ana));
                    }
                }
                """));
        assertEquals(List.of("{id=1L, name=\"ana\"}", true), c.call("app.App", "run"));
    }

    @Test
    void componentsUseTheGivensInScopeAndDerivedRecordsNest() throws Exception {
        var c = compile(Map.of("lib/Demo.java", DEMO, "app/App.java", """
                package app;
                import lib.Demo;
                public class App {
                    given Demo<String> loud = Demo.of(s -> s.toUpperCase());   // nearer than Demo.strings
                    record Pet(String name) derives Demo {}
                    record Owner(String name, Pet pet) derives Demo {}          // uses Pet.demo for pet
                    public static Object run() {
                        return Owner.demo.show(new Owner("ana", new Pet("rex")));
                    }
                }
                """));
        assertEquals("{name=ANA, pet={name=REX}}", c.call("app.App", "run"));
    }

    @Test
    void aGenericRecordGetsAGivenMethod() throws Exception {
        var c = compile(Map.of("lib/Demo.java", DEMO, "app/App.java", """
                package app;
                import forj.Implicits;
                import lib.Demo;
                public class App {
                    record Box<A>(A value, int size) derives Demo {}
                    public static Object run() {
                        Demo<Box<String>> demo = Implicits.summon();
                        return demo.show(new Box<>("x", 2));
                    }
                }
                """));
        assertEquals("{value=\"x\", size=2}", c.call("app.App", "run"));
    }

    @Test
    void severalTypeClassesAndThePlainJavaSpelling() throws Exception {
        var c = compile(Map.of("lib/Demo.java", DEMO,
                "lib/Other.java", DEMO.replace("Demo", "Other").replace("'=')", "':')"),
                "app/App.java", """
                        package app;
                        import java.util.List;
                        import lib.Demo;
                        import lib.Other;
                        public class App {
                            record A(int n) derives Demo, Other {}
                            @forj.Derives(Demo.class) record B(int n) {}
                            public static Object run() {
                                return List.of(A.demo.show(new A(1)), A.other.show(new A(1)), B.demo.show(new B(2)));
                            }
                        }
                        """));
        assertEquals(List.of("{n=1}", "{n:1}", "{n=2}"), c.call("app.App", "run"));
    }

    @Test
    void theClauseCanFollowImplementsAndNameAQualifiedTypeClass() throws Exception {
        var c = compile(Map.of("lib/Demo.java", DEMO, "app/App.java", """
                package app;
                public class App {
                    record Point(int x, int y) implements Comparable<Point> derives lib.Demo {
                        public int compareTo(Point o) { return Integer.compare(x, o.x); }
                    }
                    public static Object run() { return Point.demo.show(new Point(1, 2)); }
                }
                """));
        assertEquals("{x=1, y=2}", c.call("app.App", "run"));
    }

    @Test
    void aComponentWithoutAnInstanceIsACompileError() throws Exception {
        var c = compile(Map.of("lib/Demo.java", DEMO, "app/App.java", """
                package app;
                import lib.Demo;
                public class App {
                    record Event(String name, java.time.Instant at) derives Demo {}
                }
                """));
        assertTrue(c.errors().stream().anyMatch(e -> e.contains("forj: no given lib.Demo<java.time.Instant>")),
                c.errors()::toString);
    }
}
