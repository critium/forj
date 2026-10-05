package forj.typeclass;

import forj.Kind;
import java.util.function.Function;
import java.util.function.Predicate;

/** A {@link Monad} that can fail with an {@code E}. */
public interface MonadError<F, E> extends ApplicativeError<F, E>, Monad<F> {

    /** Fails with {@code error(a)} unless {@code a} passes {@code p}. */
    default <A> Kind<F, A> ensure(Kind<F, A> fa, Predicate<? super A> p, Function<? super A, ? extends E> error) {
        return flatMap(fa, a -> p.test(a) ? pure(a) : raiseError(error.apply(a)));
    }

    default <A> Kind<F, A> rethrow(Kind<F, forj.data.Either<E, A>> fea) {
        return flatMap(fea, ea -> ea.fold(this::raiseError, this::pure));
    }
}
