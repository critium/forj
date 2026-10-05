package forj.typeclass;

import forj.Kind;
import forj.data.Either;
import java.util.function.Function;

/** An {@link Applicative} that can fail with an {@code E} and recover. */
public interface ApplicativeError<F, E> extends Applicative<F> {

    <A> Kind<F, A> raiseError(E e);

    <A> Kind<F, A> handleErrorWith(Kind<F, A> fa, Function<? super E, ? extends Kind<F, A>> handler);

    default <A> Kind<F, A> handleError(Kind<F, A> fa, Function<? super E, ? extends A> handler) {
        return handleErrorWith(fa, e -> pure(handler.apply(e)));
    }

    /** The error as a value: {@code Left(e)} instead of a failed {@code F}. */
    default <A> Kind<F, Either<E, A>> attempt(Kind<F, A> fa) {
        return handleErrorWith(map(fa, Either::<E, A>right), e -> pure(Either.left(e)));
    }
}
