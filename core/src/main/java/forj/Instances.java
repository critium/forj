package forj;

import forj.data.CallableK;
import forj.data.ListK;
import forj.data.OptionalK;
import forj.data.Unit;
import forj.typeclass.Applicative;
import forj.typeclass.FunctorFilter;
import forj.typeclass.Monad;
import forj.typeclass.Monoid;
import forj.typeclass.Sync;
import forj.typeclass.Traverse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Type class instances for JDK types. The forj plugin always searches here last, so they
 * need no import. One object per type implements all of that type's type classes, so asking
 * for a {@code Functor<List>} finds exactly one instance.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class Instances {
    private Instances() {}

    @Given
    public static final ListInstances list = new ListInstances();

    @Given
    public static final OptionalInstances optional = new OptionalInstances();

    @Given
    public static final CallableInstances callable = new CallableInstances();

    // ------------------------------------------------------------------ Monoids

    /** Integers combine by addition. */
    @Given
    public static final Monoid<Integer> intSum = Monoid.of(0, Integer::sum);

    /** Longs combine by addition. */
    @Given
    public static final Monoid<Long> longSum = Monoid.of(0L, Long::sum);

    /** Strings combine by concatenation. */
    @Given
    public static final Monoid<String> string = Monoid.of("", String::concat);

    /** Lists combine by concatenation. */
    @Given
    public static <A> Monoid<List<A>> listConcat() {
        return Monoid.of(List.of(), (xs, ys) -> {
            List<A> out = new ArrayList<>(xs);
            out.addAll(ys);
            return Collections.unmodifiableList(out);
        });
    }

    // ---------------------------------------------------------------------- List

    public static final class ListInstances implements Monad<List>, Traverse<List>, FunctorFilter<List> {
        private ListInstances() {}

        @Override
        public <A> Kind<List, A> pure(A a) {
            return new ListK<>(List.of(a));
        }

        @Override
        public <A, B> Kind<List, B> flatMap(Kind<List, A> fa, Function<? super A, ? extends Kind<List, B>> f) {
            List<B> out = new ArrayList<>();
            for (A a : ListK.narrow(fa)) {
                out.addAll(ListK.narrow(f.apply(a)));
            }
            return new ListK<>(Collections.unmodifiableList(out));
        }

        @Override
        public <A, B> Kind<List, B> map(Kind<List, A> fa, Function<? super A, ? extends B> f) {
            List<A> as = ListK.narrow(fa);
            List<B> out = new ArrayList<>(as.size());
            for (A a : as) {
                out.add(f.apply(a));
            }
            return new ListK<>(Collections.unmodifiableList(out));
        }

        @Override
        public <A> Kind<List, A> filter(Kind<List, A> fa, Predicate<? super A> p) {
            return new ListK<>(ListK.narrow(fa).stream().filter(p).map(a -> (A) a).toList());
        }

        @Override
        public <A, B> B foldLeft(Kind<List, A> fa, B initial, BiFunction<? super B, ? super A, ? extends B> f) {
            B acc = initial;
            for (A a : ListK.narrow(fa)) {
                acc = f.apply(acc, a);
            }
            return acc;
        }

        @Override
        public <G, A, B> Kind<G, Kind<List, B>> traverse(Applicative<G> g, Kind<List, A> fa,
                                                       Function<? super A, ? extends Kind<G, B>> f) {
            Kind<G, List<B>> acc = g.pure(List.of());
            for (A a : ListK.narrow(fa)) {
                acc = g.map2(acc, f.apply(a), (bs, b) -> {
                    List<B> next = new ArrayList<>(bs);
                    next.add(b);
                    return Collections.unmodifiableList(next);
                });
            }
            return g.map(acc, ListK::new);
        }
    }

    // ------------------------------------------------------------------ Optional

    public static final class OptionalInstances implements Monad<Optional>, Traverse<Optional>, FunctorFilter<Optional> {
        private OptionalInstances() {}

        @Override
        public <A> Kind<Optional, A> pure(A a) {
            return new OptionalK<>(Optional.of(a));
        }

        @Override
        public <A, B> Kind<Optional, B> flatMap(Kind<Optional, A> fa, Function<? super A, ? extends Kind<Optional, B>> f) {
            return new OptionalK<>(OptionalK.narrow(fa).flatMap(a -> OptionalK.narrow(f.apply(a))));
        }

        @Override
        public <A, B> Kind<Optional, B> map(Kind<Optional, A> fa, Function<? super A, ? extends B> f) {
            return new OptionalK<>(OptionalK.narrow(fa).map(f));
        }

        @Override
        public <A> Kind<Optional, A> filter(Kind<Optional, A> fa, Predicate<? super A> p) {
            return new OptionalK<>(OptionalK.narrow(fa).filter(p));
        }

        @Override
        public <A, B> B foldLeft(Kind<Optional, A> fa, B initial, BiFunction<? super B, ? super A, ? extends B> f) {
            Optional<A> o = OptionalK.narrow(fa);
            return o.isEmpty() ? initial : f.apply(initial, o.get());
        }

        @Override
        public <G, A, B> Kind<G, Kind<Optional, B>> traverse(Applicative<G> g, Kind<Optional, A> fa,
                                                           Function<? super A, ? extends Kind<G, B>> f) {
            Optional<A> o = OptionalK.narrow(fa);
            return o.isEmpty()
                    ? g.pure(new OptionalK<>(Optional.empty()))
                    : g.map(f.apply(o.get()), b -> new OptionalK<>(Optional.of(b)));
        }
    }

    // ------------------------------------------------------------------ Callable

    /** Callable is a lazy effect: Sync, but not FunctorFilter (a guard has nothing to drop). */
    public static final class CallableInstances implements Sync<Callable> {
        private CallableInstances() {}

        private static <A> Kind<Callable, A> k(Callable<A> c) {
            return new CallableK<>(c);
        }

        @Override
        public <A> Kind<Callable, A> pure(A a) {
            return k(() -> a);
        }

        @Override
        public <A, B> Kind<Callable, B> flatMap(Kind<Callable, A> fa, Function<? super A, ? extends Kind<Callable, B>> f) {
            return k(() -> CallableK.narrow(f.apply(CallableK.narrow(fa).call())).call());
        }

        @Override
        public <A, B> Kind<Callable, B> map(Kind<Callable, A> fa, Function<? super A, ? extends B> f) {
            return k(() -> f.apply(CallableK.narrow(fa).call()));
        }

        @Override
        public <A> Kind<Callable, A> raiseError(Throwable e) {
            return k(() -> {
                throw e instanceof Exception ex ? ex : new RuntimeException(e);
            });
        }

        @Override
        public <A> Kind<Callable, A> handleErrorWith(Kind<Callable, A> fa,
                                                    Function<? super Throwable, ? extends Kind<Callable, A>> handler) {
            return k(() -> {
                try {
                    return CallableK.narrow(fa).call();
                } catch (Exception e) {
                    return CallableK.narrow(handler.apply(e)).call();
                }
            });
        }

        @Override
        public <A, B> Kind<Callable, B> bracket(Kind<Callable, A> acquire, Function<? super A, ? extends Kind<Callable, B>> use,
                                                Function<? super A, ? extends Kind<Callable, Unit>> release) {
            return k(() -> {
                A resource = CallableK.narrow(acquire).call();
                try {
                    return CallableK.narrow(use.apply(resource)).call();
                } finally {
                    CallableK.narrow(release.apply(resource)).call();
                }
            });
        }

        @Override
        public <A> Kind<Callable, A> suspend(Callable<? extends Kind<Callable, A>> thunk) {
            return k(() -> CallableK.narrow(thunk.call()).call());
        }

        @Override
        public <A> Kind<Callable, A> delay(Callable<? extends A> thunk) {
            return k(thunk::call);
        }
    }
}
